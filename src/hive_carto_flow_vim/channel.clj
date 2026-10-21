(ns hive-carto-flow-vim.channel
  "Boundary: this addon's instance of hive-vessel's :vim-channel executor
   (`hive-vessel.executor.vim-channel`), plus what that executor leaves to its
   caller: an accept loop, a feature handshake per connection, and the vessel
   descriptor of the live connection.

   The executor holds one connection. Each accepted Vim replaces the previous
   one, is probed for the features it advertises (through `dispatch!`, as a
   `:vim/call` of Vim's builtin eval), and then ON-CONNECT runs with the new
   descriptor. Deliveries resolve `vessel` per call; a vanished Vim surfaces as
   an executor throw on the next call."
  (:require [clojure.data.json :as json]
            [hive-carto-flow-vim.vessel :as vim-vessel]
            [hive-vessel.core :as v]
            [hive-vessel.executor.vim-channel :as vc])
  (:import (java.net ServerSocket Socket SocketTimeoutException)))

(def thread-name "hive-carto-flow-vim-acceptor")

(def handshake-thread-prefix
  "Name prefix of the per-connection handshake threads. It shares
   `thread-name`'s stem on purpose: lifecycle checks that no addon thread
   survives a shutdown see both."
  "hive-carto-flow-vim-acceptor-handshake-")

(def accept-poll-ms
  "How long one accept waits before the loop rechecks that it should run."
  250)

(def default-call-timeout-ms 10000)

(def default-probe-timeout-ms 2000)

(defn- close-quietly
  [^Socket socket]
  (when socket
    (try (.close socket) (catch Throwable _ nil))))

(defn decode-reply
  "The JSON text of a Vim reply as data, or nil when it is not JSON."
  [text]
  (try (json/read-str text) (catch Throwable _ nil)))

(defn probe-features
  "Ask the Vim behind TARGET what it advertises and which of the functions
   those features entitle the server to call it defines. Returns
   {:features #{kw ...}} holding only the confirmed features (see
   `hive-carto-flow-vim.vessel/confirm-features`) and, when the handshake
   failed, :error."
  [registry target]
  (let [result (v/dispatch! registry target vim-vessel/features-probe-op)]
    (if-let [results (get-in result [:ok :plan/results])]
      {:features (vim-vessel/confirm-features (decode-reply (first results)))}
      {:features #{} :error (:error result)})))

(defn- pinned-server
  "SERVER as seen through CONN, one accepted connection. hive-vessel's
   send-command! writes to whatever `(:conn server)` holds at call time, so a
   descriptor built over SERVER itself follows every later accept, and a
   presenter bound to one Vim would deliver into the next before that Vim's
   hello. Over the pinned view a call reaches only this connection and fails
   once it is closed."
  [server conn]
  (assoc server :conn (atom conn)))

(def default-liveness-timeout-ms
  "How long the live Vim gets to answer the liveness ping a second client's
   connect sends it."
  1000)

(def ^:private liveness-payload
  "A channel expr every Vim answers, used only to ask whether a connection
   still has a Vim behind it."
  ["expr" "1"])

(defn- live?
  "True when the Vim behind CONN answers a ping over SERVER within TIMEOUT-MS."
  [server conn timeout-ms]
  (boolean
   (when-let [^Socket socket (:socket conn)]
     (and (not (.isClosed socket))
          (try (vc/send-command! (pinned-server server conn) liveness-payload timeout-ms)
               true
               (catch Throwable _ false))))))

(defn- connected!
  "Handshake CONN, the connection just admitted, and run ON-CONNECT with its
   descriptor. Runs on a handshake thread of its own, never on the acceptor:
   the sync, the probe and ON-CONNECT (which replays the timeline) all wait
   on the Vim. A handshake whose connection was replaced meanwhile publishes
   nothing, so a slow, stale handshake never overwrites the current Vim."
  [{:keys [server registry call-timeout-ms probe-timeout-ms vessel
           handshake last-error sync!] :as ch}
   conn on-connect]
  (let [current? #(identical? conn @(:conn server))
        conn-server (pinned-server server conn)
        call-target (assoc (vc/target conn-server)
                           :vessel/execute! (vc/executor conn-server call-timeout-ms))
        ;; Bring the Vim's plugin up to this runtime BEFORE the probe, so the
        ;; features it reports are the reloaded script's.
        runtime (when sync!
                  (try (sync! registry call-target)
                       (catch Throwable t {:error (or (ex-message t) (str t))})))
        probe-target (assoc (vc/target conn-server)
                            :vessel/execute! (vc/executor conn-server probe-timeout-ms))
        {:keys [features error]} (probe-features registry probe-target)
        descriptor (cond-> call-target
                     (seq features) (assoc :vessel/features features))]
    (when (current?)
      (reset! handshake (cond-> {:features features}
                          error (assoc :error error)
                          runtime (assoc :runtime runtime)))
      (reset! vessel descriptor)
      (try
        (when on-connect (on-connect ch descriptor))
        (catch Throwable t
          (reset! last-error (or (ex-message t) (str t))))))))

(defn- admit!
  "Decide what the connection ACCEPTED does to the channel, on the acceptor.
   A Vim still live on PREVIOUS keeps the channel: ACCEPTED is closed and
   counted under :refused, so two Vims sharing the port no longer evict each
   other (each eviction used to run a full handshake and timeline replay, and
   the evicted Vim's reconnect timer evicted the other in turn). Otherwise
   ACCEPTED replaces PREVIOUS and its handshake starts on its own thread.
   Returns :refused or :admitted."
  [{:keys [server liveness-timeout-ms vessel connections refused] :as ch}
   previous accepted on-connect]
  (if (and previous (live? server previous liveness-timeout-ms))
    (do (reset! (:conn server) previous)
        (close-quietly (:socket accepted))
        (swap! refused inc)
        :refused)
    (do (close-quietly (:socket previous))
        (reset! vessel nil)
        (swap! connections inc)
        (doto (Thread. ^Runnable #(connected! ch accepted on-connect)
                       ^String (str handshake-thread-prefix @connections))
          (.setDaemon true)
          (.start))
        :admitted)))

(defn- accept-loop
  [{:keys [server running? last-error] :as ch} on-connect]
  (while @running?
    (let [previous @(:conn server)
          accepted? (try
                      (vc/await-vim! server accept-poll-ms)
                      true
                      (catch SocketTimeoutException _ false)
                      (catch Throwable t
                        (when @running?
                          (reset! last-error (or (ex-message t) (str t))))
                        (reset! running? false)
                        false))]
      (when accepted?
        (try
          (admit! ch previous @(:conn server) on-connect)
          (catch Throwable t
            (reset! last-error (or (ex-message t) (str t)))))))))

(defn start!
  "Start a hive-vessel vim-channel executor on PORT (0 picks a free one) and
   an accept loop. Each admitted connection first runs SYNC!,
   (fn [registry target]), whose return is kept as the handshake's :runtime,
   then the feature probe; ON-CONNECT, (fn [channel vessel]), runs after both,
   on that connection's handshake thread. While a Vim is live a second one is
   refused (see `admit!`); LIVENESS-TIMEOUT-MS bounds the ping deciding that.
   Throws when the port cannot be bound."
  [{:keys [port registry on-connect sync! call-timeout-ms probe-timeout-ms
           liveness-timeout-ms]}]
  (let [server (vc/start! {:port (or port 0)})
        ch {:server server
            :registry (or registry (vim-vessel/registry))
            :sync! sync!
            :call-timeout-ms (or call-timeout-ms default-call-timeout-ms)
            :probe-timeout-ms (or probe-timeout-ms default-probe-timeout-ms)
            :liveness-timeout-ms (or liveness-timeout-ms default-liveness-timeout-ms)
            :vessel (atom nil)
            :handshake (atom nil)
            :connections (atom 0)
            :refused (atom 0)
            :last-error (atom nil)
            :running? (atom true)}
        thread (doto (Thread. ^Runnable #(accept-loop ch on-connect) ^String thread-name)
                 (.setDaemon true)
                 (.start))]
    (assoc ch :thread thread)))

(defn stop!
  "Stop the accept loop and the executor, closing the Vim connection."
  [{:keys [server running? ^Thread thread]}]
  (reset! running? false)
  (try (vc/stop! server) (catch Throwable _ nil))
  (when thread
    (.join thread 2000)))

(defn resync!
  "Run the channel's SYNC! against the Vim connected now, over the existing
   connection, and keep its return as the handshake's :runtime, tagged with
   :trigger TRIGGER. No reconnect, no replay. Returns that runtime map, or nil
   when no Vim is connected or the channel has no SYNC!. Never throws."
  [{:keys [registry sync! handshake] :as ch} trigger]
  (when-let [target (and sync! (some-> ch :vessel deref))]
    (let [runtime (assoc (try (sync! registry target)
                              (catch Throwable t {:error (or (ex-message t) (str t))}))
                         :trigger trigger)]
      (swap! handshake assoc :runtime runtime)
      runtime)))

(defn port [ch] (get-in ch [:server :port]))

(defn vessel
  "The vessel descriptor of the connected Vim, or nil before the first
   handshake."
  [ch]
  (some-> ch :vessel deref))

(defn listening?
  [ch]
  (boolean (some-> ^ServerSocket (get-in ch [:server :server-socket]) .isClosed not)))

(defn status
  [{:keys [server handshake connections refused last-error] :as ch}]
  (let [features (:features @handshake #{})]
    (cond-> {:listening? (listening? ch)
             :port (port ch)
             :vim-attached? (vc/connected? server)
             :connections @connections
             :refused (if refused @refused 0)
             :features features
             :timeline-feature? (contains? features vim-vessel/timeline-feature)}
      (:error @handshake) (assoc :handshake-error (:error @handshake))
      (:runtime @handshake) (assoc :runtime (:runtime @handshake))
      @last-error (assoc :last-error @last-error))))

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
  "Ask the Vim behind TARGET what it advertises. Returns
   {:features #{kw ...}} and, when the handshake failed, :error."
  [registry target]
  (let [result (v/dispatch! registry target vim-vessel/features-probe-op)]
    (if-let [results (get-in result [:ok :plan/results])]
      {:features (vim-vessel/parse-features (decode-reply (first results)))}
      {:features #{} :error (:error result)})))

(defn- connected!
  [{:keys [server registry call-timeout-ms probe-timeout-ms vessel connections
           handshake last-error] :as ch}
   on-connect]
  (reset! vessel nil)
  (swap! connections inc)
  (let [probe-target (assoc (vc/target server)
                            :vessel/execute! (vc/executor server probe-timeout-ms))
        {:keys [features error]} (probe-features registry probe-target)
        descriptor (cond-> (assoc (vc/target server)
                                  :vessel/execute! (vc/executor server call-timeout-ms))
                     (seq features) (assoc :vessel/features features))]
    (reset! handshake (cond-> {:features features} error (assoc :error error)))
    (reset! vessel descriptor)
    (try
      (when on-connect (on-connect ch descriptor))
      (catch Throwable t
        (reset! last-error (or (ex-message t) (str t)))))))

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
        (close-quietly (:socket previous))
        (connected! ch on-connect)))))

(defn start!
  "Start a hive-vessel vim-channel executor on PORT (0 picks a free one) and
   an accept loop. ON-CONNECT, (fn [channel vessel]), runs on the loop thread
   after each connection's handshake. Throws when the port cannot be bound."
  [{:keys [port registry on-connect call-timeout-ms probe-timeout-ms]}]
  (let [server (vc/start! {:port (or port 0)})
        ch {:server server
            :registry (or registry (vim-vessel/registry))
            :call-timeout-ms (or call-timeout-ms default-call-timeout-ms)
            :probe-timeout-ms (or probe-timeout-ms default-probe-timeout-ms)
            :vessel (atom nil)
            :handshake (atom nil)
            :connections (atom 0)
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
  [{:keys [server handshake connections last-error] :as ch}]
  (let [features (:features @handshake #{})]
    (cond-> {:listening? (listening? ch)
             :port (port ch)
             :vim-attached? (vc/connected? server)
             :connections @connections
             :features features
             :timeline-feature? (contains? features vim-vessel/timeline-feature)}
      (:error @handshake) (assoc :handshake-error (:error @handshake))
      @last-error (assoc :last-error @last-error))))

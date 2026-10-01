(ns hive-carto-flow-vim.watch
  "A daemon thread that polls a content hash and calls back when it settles on
   a new value. The addon points it at `paths/runtime-hash`, so editing the
   carto_flow plugin in a source checkout reaches the connected Vim without a
   remount.

   A change fires once the hash has held still for one poll, so a file caught
   halfway through a save is never pushed. Nothing the hash function or the
   callback throws escapes the thread: it is recorded as :last-error and the
   next poll tries again."
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(def thread-name "hive-carto-flow-vim-runtime-watch")

(def default-interval-ms 500)

(defn- message
  [^Throwable t]
  (or (ex-message t) (str t)))

(defn poll!
  "One poll step. Reads HASH-FN, and when the hash differs from the last one
   applied and equals the one seen on the previous poll, calls ON-CHANGE with
   it. Returns nil; every outcome lands in WATCH's :state atom."
  [{:keys [hash-fn on-change state]}]
  (try
    (let [h (hash-fn)
          {:keys [applied seen]} @state]
      (swap! state assoc :seen h)
      (when (and (not= h applied) (= h seen))
        (swap! state assoc :applied h)
        (try
          (let [result (on-change h)]
            (swap! state #(-> % (update :changes (fnil inc 0)) (assoc :last-result result)
                              (dissoc :last-error))))
          (catch Throwable t
            (swap! state assoc :last-error (message t))))))
    (catch Throwable t
      (swap! state assoc :last-error (message t))))
  nil)

(defn start!
  "Start watching. HASH-FN is read once now as the baseline, so content that
   never changes never fires ON-CHANGE, (fn [hash]). Returns a watch handle
   for `stop!` and `status`."
  [{:keys [hash-fn on-change interval-ms]}]
  (let [baseline (try (hash-fn) (catch Throwable _ nil))
        interval-ms (long (or interval-ms default-interval-ms))
        stopped (CountDownLatch. 1)
        w {:hash-fn hash-fn
           :on-change on-change
           :interval-ms interval-ms
           :stopped stopped
           :state (atom {:applied baseline :seen baseline :changes 0})}
        thread (doto (Thread.
                      ^Runnable
                      (fn []
                        (while (not (.await stopped interval-ms TimeUnit/MILLISECONDS))
                          (poll! w)))
                      ^String thread-name)
                 (.setDaemon true)
                 (.start))]
    (assoc w :thread thread)))

(defn stop!
  "Stop the watch thread. Never throws."
  [{:keys [^CountDownLatch stopped ^Thread thread]}]
  (when stopped (.countDown stopped))
  (when thread
    (try (.join thread 2000) (catch Throwable _ nil)))
  nil)

(defn status
  [{:keys [state interval-ms ^Thread thread]}]
  (let [{:keys [applied changes last-error]} @state]
    (cond-> {:watching? (boolean (and thread (.isAlive thread)))
             :interval-ms interval-ms
             :hash applied
             :changes changes}
      last-error (assoc :last-error last-error))))

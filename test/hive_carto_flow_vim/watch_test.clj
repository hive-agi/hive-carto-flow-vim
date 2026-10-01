(ns hive-carto-flow-vim.watch-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-carto-flow-vim.paths :as paths]
            [hive-carto-flow-vim.test-support :as t]
            [hive-carto-flow-vim.watch :as watch]))

(defn- handle
  [hash-fn on-change baseline]
  {:hash-fn hash-fn :on-change on-change
   :state (atom {:applied baseline :seen baseline :changes 0})})

(deftest poll-fires-once-per-settled-change-and-never-on-unchanged-content
  (let [h (atom "a")
        fired (atom [])
        w (handle #(deref h) #(do (swap! fired conj %) {:ok %}) "a")]
    (dotimes [_ 3] (watch/poll! w))
    (is (= [] @fired) "unchanged content never fires")
    (reset! h "b")
    (watch/poll! w)
    (is (= [] @fired) "a new hash waits one poll to settle")
    (watch/poll! w)
    (is (= ["b"] @fired))
    (dotimes [_ 3] (watch/poll! w))
    (is (= ["b"] @fired) "a change fires once")
    (is (= {:ok "b"} (:last-result @(:state w))))
    (reset! h "c")
    (watch/poll! w)
    (reset! h "d")
    (watch/poll! w)
    (watch/poll! w)
    (is (= ["b" "d"] @fired) "a hash still moving is skipped until it holds")))

(deftest poll-never-throws
  (testing "the hash fn throwing"
    (let [w (handle #(throw (ex-info "boom" {})) (fn [_] (throw (ex-info "never" {}))) "a")]
      (is (nil? (watch/poll! w)))
      (is (= "boom" (:last-error @(:state w))))))
  (testing "the callback throwing"
    (let [w (handle (constantly "b") (fn [_] (throw (ex-info "sync failed" {}))) "a")]
      (watch/poll! w)
      (is (nil? (watch/poll! w)))
      (is (= "sync failed" (:last-error @(:state w))))
      (is (= "b" (:applied @(:state w))) "a failed push is not retried in a loop"))))

(deftest the-watch-thread-starts-fires-and-stops
  (let [h (atom "a")
        fired (atom [])
        w (watch/start! {:hash-fn #(deref h) :on-change #(swap! fired conj %) :interval-ms 10})]
    (try
      (is (:watching? (watch/status w)))
      (Thread/sleep 60)
      (is (= [] @fired))
      (reset! h "b")
      (is (t/eventually #(= ["b"] @fired) 2000))
      (is (t/eventually #(= {:hash "b" :changes 1} (select-keys (watch/status w) [:hash :changes])) 2000)
          "the change is counted once the callback returns")
      (finally (watch/stop! w)))
    (is (not (:watching? (watch/status w))) "stop! ends the thread")))

(deftest a-runtime-root-replaces-the-classpath-plugin
  (let [root (t/temp-dir)
        dir (t/temp-dir)]
    (try
      (doseq [rel paths/plugin-files]
        (io/make-parents (io/file root rel))
        (spit (io/file root rel) (slurp (io/resource (str paths/plugin-resource-root rel)))))
      (is (= (paths/runtime-hash) (paths/runtime-hash root)) "same content, same hash")
      (is (paths/runtime-watchable? root))
      (spit (io/file root "plugin/carto_flow.vim") "\" edited\n" :append true)
      (is (not= (paths/runtime-hash) (paths/runtime-hash root)))
      (let [plugin-dir (paths/ensure-plugin-dir! dir root)]
        (is (= (slurp (io/file root "plugin/carto_flow.vim"))
               (slurp (io/file plugin-dir "plugin/carto_flow.vim"))))
        (is (.isFile (io/file plugin-dir "plugin/hive_vessel.vim"))
            "hive-vessel's plugin still comes from the classpath"))
      (finally (t/delete-tree! root) (t/delete-tree! dir)))))

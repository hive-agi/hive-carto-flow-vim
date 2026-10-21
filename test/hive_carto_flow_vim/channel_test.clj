(ns hive-carto-flow-vim.channel-test
  "Two Vims sharing the channel port: the second is refused while the first is
   live, so neither evicts the other and the timeline is not replayed on every
   reconnect; the handshake and ON-CONNECT run off the acceptor thread."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-carto-flow-vim.channel :as channel]
            [hive-carto-flow-vim.test-support :as t]
            [hive-carto-flow-vim.vessel :as vim-vessel]))

(defn- recording-on-connect
  "An ON-CONNECT recording the thread it ran on for every connection."
  [seen]
  (fn [_ch _vessel]
    (swap! seen conj (.getName (Thread/currentThread)))))

(deftest a-second-vim-does-not-evict-the-live-one
  (let [seen (atom [])
        ch (channel/start! {:port 0
                            :registry (vim-vessel/registry)
                            :on-connect (recording-on-connect seen)})
        a (t/fake-vim (channel/port ch))]
    (try
      (is (t/eventually #(= 1 (count @seen))) "the first Vim is handshaken")
      (let [vessel-a (channel/vessel ch)
            b (t/fake-vim (channel/port ch))]
        (try
          (testing "the second Vim is refused, not admitted"
            (is (t/eventually #(= 1 (:refused (channel/status ch)))))
            (is (t/vim-closed? b) "the refused socket is closed"))
          (testing "the first Vim keeps the channel and is not replayed again"
            (is (= 1 (:connections (channel/status ch))))
            (is (= 1 (count @seen)))
            (is (identical? vessel-a (channel/vessel ch)))
            (is (:vim-attached? (channel/status ch)))
            (is (true? @(:running? a)) "the live Vim's socket is still open")
            (is (some #(= "expr" (first %)) @(:commands a))
                "the live Vim was pinged, not closed"))
          (finally (t/close-vim! b))))
      (testing "once the live Vim is gone the next one is admitted"
        (t/close-vim! a)
        (let [c (t/fake-vim (channel/port ch))]
          (try
            (is (t/eventually #(= 2 (count @seen))))
            (is (= 2 (:connections (channel/status ch))))
            (finally (t/close-vim! c)))))
      (testing "the handshake ran off the acceptor thread"
        (is (every? #(.startsWith ^String % channel/handshake-thread-prefix) @seen))
        (is (not-any? #{channel/thread-name} @seen)))
      (finally (channel/stop! ch)))))

(deftest repeated-reconnect-attempts-do-not-replay
  (let [seen (atom [])
        ch (channel/start! {:port 0
                            :registry (vim-vessel/registry)
                            :on-connect (recording-on-connect seen)})
        a (t/fake-vim (channel/port ch))]
    (try
      (is (t/eventually #(= 1 (count @seen))))
      (dotimes [_ 5]
        (let [other (t/fake-vim (channel/port ch))]
          (t/vim-closed? other)
          (t/close-vim! other)))
      (is (t/eventually #(= 5 (:refused (channel/status ch)))))
      (is (= 1 (count @seen)) "one handshake (and one replay) for the live Vim only")
      (finally
        (t/close-vim! a)
        (channel/stop! ch)))))

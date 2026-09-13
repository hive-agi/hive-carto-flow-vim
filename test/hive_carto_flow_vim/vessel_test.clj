(ns hive-carto-flow-vim.vessel-test
  "Pure lowering: the same frame intent reaches a Vim running the carto_flow
   plugin as a timeline call, and every other Vim as hive-vessel's generic
   panel. No sockets."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-carto-flow.render.text :as text]
            [hive-carto-flow-vim.vessel :as sut]
            [hive-vessel.core :as v]))

(def frame
  {:frame/index 2
   :frame/phase :succeeded
   :op/command "write-form"
   :affected/paths ["src/a.clj"]
   :frame/summary "write-form succeeded"
   :frame/diff "--- a\n+++ b\n+x"})

(def intent {:op :carto-flow/frame :frame frame})

(defn- plan-ops
  [target]
  (:plan/ops (:ok (v/plan (sut/registry) target intent))))

(defn- payloads
  [target]
  (mapv :native/payload (plan-ops target)))

(def timeline-target
  {:vessel/id :vim :vessel/dialect :vim-channel
   :vessel/features #{sut/timeline-feature}})

(def plain-target
  {:vessel/id :vim :vessel/dialect :vim-channel})

(deftest a-vim-running-the-plugin-gets-the-timeline-call
  (let [[payload :as all] (payloads timeline-target)]
    (is (= 1 (count all)))
    (is (= "call" (first payload)))
    (is (= sut/ingest-fn (second payload)))
    (let [msg (first (nth payload 2))]
      (is (= 2 (get msg "index")))
      (is (= "succeeded" (get msg "phase")))
      (is (= (text/frame-line frame) (get msg "line")) "the line is core's frame-line")
      (is (= (vec (text/detail-lines frame)) (get msg "detail")))
      (is (= "src/a.clj" (first (get-in msg ["frame" "affected/paths"])))
          "the frame rides along, JSON-safe"))))

(deftest a-vim-without-the-plugin-falls-back-to-the-generic-panel
  (let [[payload :as all] (payloads plain-target)]
    (is (= 1 (count all)))
    (is (= ["call" "hive_vessel#show_panel"] (subvec payload 0 2))
        "core's generic translator lowers through hive-vessel's own dialect")
    (let [[panel-id title lines] (nth payload 2)]
      (is (= "carto-flow" panel-id))
      (is (str/includes? title "#2"))
      (is (some #(str/includes? (get % "text") "write-form") lines)))))

(deftest a-failed-frame-also-notifies-on-both-paths
  (let [failed (assoc frame :frame/phase :failed :frame/index 3)
        ops (fn [target]
              (mapv (comp second :native/payload)
                    (:plan/ops (:ok (v/plan (sut/registry) target
                                            {:op :carto-flow/frame :frame failed})))))]
    (is (some #{"hive_vessel#notify"} (ops plain-target)))
    (is (= [sut/ingest-fn] (ops timeline-target))
        "the plugin renders failure itself; no duplicate notification")))

(deftest an-envelope-carries-the-frame-under-payload
  (is (= (sut/ingest-op {:frame frame} timeline-target)
         (sut/ingest-op {:payload frame} timeline-target))))

(deftest features-are-read-from-what-vim-answers
  (testing "a JSON list of names becomes keywords"
    (is (= #{:carto-flow/timeline} (sut/parse-features ["carto-flow/timeline"])))
    (is (= #{:carto-flow/timeline :x/y} (sut/parse-features ["carto-flow/timeline" "x/y"]))))
  (testing "anything else advertises nothing, so lowering degrades"
    (is (= #{} (sut/parse-features nil)))
    (is (= #{} (sut/parse-features "carto-flow/timeline")))
    (is (= #{} (sut/parse-features [""])))
    (is (= #{} (sut/parse-features 42)))))

(deftest the-timeline-translator-is-well-formed-data
  (let [t sut/timeline-translator]
    (is (= :carto-flow/frame (:translator/op t)))
    (is (= {:vessel/dialect :vim-channel :vessel/features #{:carto-flow/timeline}}
           (:translator/when t)))
    (is (ifn? (:translator/translate t)))))

(deftest hello-announces-the-backlog-size
  (is (= ["call" sut/hello-fn [{"server" sut/server-name "frames" 3}]]
         (-> (v/plan (sut/registry) timeline-target (sut/hello-op 3))
             :ok :plan/ops first :native/payload))))

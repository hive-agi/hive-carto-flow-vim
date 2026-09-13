(ns hive-carto-flow-vim.mount-test
  "The extension mounted on core, driven by a fake Vim over hive-vessel's
   :vim-channel executor."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [hive-carto-flow.render.text :as text]
            [hive-carto-flow-vim.addon :as vim-addon]
            [hive-carto-flow-vim.paths :as paths]
            [hive-carto-flow-vim.test-support :as t]
            [hive-carto-flow-vim.vessel :as vim-vessel]
            [hive-vessel.core :as v])
  (:import (java.net InetAddress ServerSocket)))

(def ^:private gated-ids #{"hive.carto-flow" "hive.carto-flow.vim"})

(defn- specs
  [state-dir port]
  [t/carto-spec
   (t/classpath-manifest "hive-carto-flow.edn")
   (update (t/classpath-manifest "hive-carto-flow-vim.edn") :addon/config merge
           {:carto-flow.vim/state-dir (str state-dir)
            :carto-flow.vim/port port})])

(defn- mount-all!
  [state-dir port]
  (let [host (mount/atom-mount-host)
        report (mount/mount! (mount/solve (specs state-dir port)) host
                             {:license-gate (t/permit-only gated-ids)})]
    {:host host :report report}))

(deftest a-vim-running-the-plugin-is-greeted-replayed-and-then-followed-live
  (let [dir (t/temp-dir)
        {:keys [host report]} (mount-all! dir 0)
        vim-ext (mount-port/registered host "hive.carto-flow.vim")
        flow (mount-port/registered host "hive.carto-flow")
        manifest (t/classpath-manifest "hive-carto-flow-vim.edn")
        port ((:carto-flow.vim/port (addon/hooks vim-ext)))]
    (try
      (testing "manifest and mount"
        (is (= #{"hive.carto-flow"} (:addon/dependencies manifest)))
        (is (= :foss (:addon/trust-class manifest)))
        (is (:ok? report) (pr-str (:mounted report)))
        (is (= "hive.carto-flow.vim" (last (:order report))))
        (is (= #{:carto-flow-presenter :health-reporting} (addon/capabilities vim-ext)))
        (is (= vim-vessel/translators (get (addon/hooks vim-ext) v/hook-key))
            "the addon contributes its translators through hive-vessel's hook"))
      (is (pos? port))
      (is (= port (paths/read-port-file (paths/port-file {} dir)))
          "the port file names the live port")
      (testing "no Vim yet: the frame is kept, not lost"
        (t/mutate! :carto.mutation/intent ["src/a.clj"])
        (is (= :degraded (get-in (addon/health flow) [:details :presenters :vim]))))
      (let [vim (t/fake-vim port #{:carto-flow/timeline})]
        (try
          (testing "handshake, greeting and backlog replay"
            (is (t/eventually #(seq (t/calls-of vim "eval"))) "features are probed")
            (is (t/eventually #(seq (t/calls-of vim "carto_flow#hello"))))
            (is (= [{"server" "hive.carto-flow.vim" "frames" 1}]
                   (nth (first (t/calls-of vim "carto_flow#hello")) 2)))
            (is (t/eventually #(= 1 (count (t/ingest-messages vim)))) "the backlog replays")
            (is (= 0 (get (first (t/ingest-messages vim)) "index"))))
          (testing "health reflects the connected Vim"
            ;; The fake Vim sees the replayed frame while core is still inside
            ;; the re-registration (attach! replays, THEN the new presenter is
            ;; swapped in), so the old degraded presenter can still be the one
            ;; health reads for a moment. Health settles; it is not instant.
            (is (t/eventually #(= :ok (:status (addon/health vim-ext))))
                "health settles to :ok once the replayed presenter is registered")
            (let [{:keys [details]} (addon/health vim-ext)]
              (is (true? (:listening? details)))
              (is (true? (:vim-attached? details)))
              (is (true? (:timeline-feature? details)))
              (is (= #{:carto-flow/timeline} (:features details)))))
          (testing "live frames follow"
            (t/mutate! :carto.mutation/succeeded ["src/a.clj" "src/b.clj"])
            (is (t/eventually #(= 2 (count (t/ingest-messages vim)))))
            (let [msg (second (t/ingest-messages vim))
                  frames (:timeline/frames ((:carto-flow/timeline (addon/hooks flow))))]
              (is (= 1 (get msg "index")))
              (is (= "succeeded" (get msg "phase")))
              (is (= (text/frame-line (second frames)) (get msg "line"))
                  "the rendered line is core's frame-line")
              (is (= "src/a.clj" (first (get-in msg ["frame" "affected/paths"]))))))
          (testing "teardown closes the Vim connection and cleans up"
            (let [teardown (mount/teardown! host (:order report))]
              (is (empty? (:errors teardown))))
            (is (t/vim-closed? vim))
            (is (nil? (paths/read-port-file (paths/port-file {} dir)))
                "the port file is removed")
            (is (t/no-acceptor-threads?))
            (is (= {} (addon/hooks vim-ext))))
          (finally (t/close-vim! vim))))
      (finally
        (addon/shutdown! vim-ext)
        (when flow (addon/shutdown! flow))
        (t/delete-tree! dir)))))

(deftest a-vim-without-the-plugin-still-gets-the-generic-panel
  (let [dir (t/temp-dir)
        {:keys [host report]} (mount-all! dir 0)
        vim-ext (mount-port/registered host "hive.carto-flow.vim")
        flow (mount-port/registered host "hive.carto-flow")
        port ((:carto-flow.vim/port (addon/hooks vim-ext)))]
    (try
      (let [vim (t/fake-vim port #{})]
        (try
          (is (t/eventually #(seq (t/calls-of vim "eval"))))
          (is (false? (:timeline-feature? (:details (addon/health vim-ext)))))
          (t/mutate! :carto.mutation/succeeded ["src/a.clj"])
          (is (t/eventually #(seq (t/calls-of vim "hive_vessel#show_panel")))
              "lowering degrades to hive-vessel's own panel")
          (is (empty? (t/ingest-messages vim)) "and never calls the absent plugin")
          (is (empty? (t/calls-of vim "carto_flow#hello")))
          (is (= :live (get-in (addon/health flow) [:details :presenters :vim])))
          (finally (t/close-vim! vim))))
      (finally
        (mount/teardown! host (:order report))
        (addon/shutdown! vim-ext)
        (when flow (addon/shutdown! flow))
        (t/delete-tree! dir)))))

(deftest initialize-without-core-fails-cleanly
  (let [dir (t/temp-dir)
        vim (vim-addon/addon-ctor {:carto-flow.vim/state-dir (str dir)})]
    (try
      (let [result (addon/initialize! vim {})]
        (is (false? (:success? result)))
        (is (seq (:errors result))))
      (is (= :down (:status (addon/health vim))))
      (is (nil? (paths/read-port-file dir)))
      (is (t/no-acceptor-threads?))
      (finally
        (addon/shutdown! vim)
        (t/delete-tree! dir)))))

(deftest a-bind-failure-fails-initialize-cleanly
  (let [dir (t/temp-dir)]
    (with-open [taken (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
      (let [{:keys [host report]} (mount-all! dir (.getLocalPort taken))
            vim (mount-port/registered host "hive.carto-flow.vim")
            flow (mount-port/registered host "hive.carto-flow")
            vim-result (some #(when (= "hive.carto-flow.vim" (:addon/id %)) %) (:mounted report))]
        (try
          (is (not (:ok? report)))
          (is (false? (:success? vim-result)) (pr-str vim-result))
          (is (nil? (paths/read-port-file dir)))
          (is (t/no-acceptor-threads?))
          (is (empty? (get-in (addon/health flow) [:details :presenters]))
              "no presenter is left registered on core")
          (finally
            (when vim (addon/shutdown! vim))
            (when flow (addon/shutdown! flow))
            (t/delete-tree! dir)))))))

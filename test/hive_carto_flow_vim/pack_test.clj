(ns hive-carto-flow-vim.pack-test
  "The provisioned install (hive-addon's runtime provisioner, under a temp
   home) is kept current: a stale copy is rewritten when the addon initializes
   again and when the runtime watch sees an edit; an absent install is never
   created; the generated loader and files the provisioner did not put there
   are never touched."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [hive-addon.runtime.boundary :as runtime]
            [hive-carto-flow-vim.pack :as pack]
            [hive-carto-flow-vim.paths :as paths]
            [hive-carto-flow-vim.test-support :as t]))

(def ^:private gated-ids #{"hive.carto-flow" "hive.carto-flow.vim"})

(def ^:private plugin "plugin/carto_flow.vim")

(def ^:private loader "plugin/zz_hive_runtime.vim")

(defn- install-dir
  [home]
  (io/file home ".vim" "pack" "hive" "start" "hive-carto-flow"))

(defn- runtime-root!
  "A directory laid out like resources/vim, holding the classpath plugin."
  [root]
  (doseq [rel paths/plugin-files]
    (io/make-parents (io/file root rel))
    (spit (io/file root rel) (slurp (io/resource (str paths/plugin-resource-root rel)))))
  root)

(defn- vim-config
  [dir home root extra]
  (merge {:carto-flow.vim/state-dir (str (io/file dir "state"))
          :carto-flow.vim/home (str home)
          :carto-flow.vim/runtime-root (str root)
          :carto-flow.vim/watch-interval-ms 50}
         extra))

(defn- mount-vim!
  [config provision]
  (let [host (mount/atom-mount-host)
        report (mount/mount!
                (mount/solve [t/carto-spec
                              (t/classpath-manifest "hive-carto-flow.edn")
                              (update (t/classpath-manifest "hive-carto-flow-vim.edn")
                                      :addon/config merge config)])
                host
                (cond-> {:license-gate (t/permit-only gated-ids)}
                  provision (assoc :provision provision)))]
    {:host host :report report
     :vim (mount-port/registered host "hive.carto-flow.vim")
     :flow (mount-port/registered host "hive.carto-flow")}))

(defn- pack-refresh
  [vim]
  (:pack-refresh (:details (addon/health vim))))

(deftest a-stale-install-is-refreshed-on-initialize-and-on-a-watched-edit
  (let [dir (t/temp-dir)
        home (io/file dir "home")
        root (runtime-root! (io/file dir "root"))
        p (runtime/provisioner {:home (str home) :live? false})
        config (vim-config dir home root {:carto-flow.vim/installed (:installed p)})
        {:keys [host report vim flow]} (mount-vim! config (:provision p))
        installed (install-dir home)]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (.isFile (io/file installed plugin)) "the provisioner installed the plugin")
      (testing "the first initialize runs before provisioning, so there is nothing to refresh"
        (is (not-any? #{:refreshed :error} (map :status (:installs (pack-refresh vim))))))
      (let [loader-text (slurp (io/file installed loader))
            foreign (io/file installed "plugin/not_from_the_provisioner.vim")
            current (slurp (io/file root plugin))]
        (spit foreign "\" mine\n")
        (spit (io/file installed plugin) "\" stale copy\n")
        (testing "initialize again: the stale file is rewritten, nothing else"
          (addon/shutdown! vim)
          (is (:success? (addon/initialize! vim (assoc config :mount/dependencies {"hive.carto-flow" flow}))))
          (let [r (first (:installs (pack-refresh vim)))]
            (is (= :refreshed (:status r)) (pr-str r))
            (is (= [plugin] (:written r)) "only the changed file is written")
            (is (= :initialize (:trigger (pack-refresh vim)))))
          (is (= current (slurp (io/file installed plugin))))
          (is (= loader-text (slurp (io/file installed loader))) "the loader is untouched")
          (is (= "\" mine\n" (slurp foreign)) "a file the provisioner did not put there is untouched"))
        (testing "an unchanged install is reported current"
          (addon/shutdown! vim)
          (addon/initialize! vim (assoc config :mount/dependencies {"hive.carto-flow" flow}))
          (is (= :current (:status (first (:installs (pack-refresh vim)))))))
        (testing "a watched edit of the plugin reaches the install"
          (let [edited (str current "\" edited while running\n")]
            (spit (io/file root plugin) edited)
            (is (t/eventually #(= edited (slurp (io/file installed plugin))) 5000))
            (is (t/eventually #(= :watch (:trigger (pack-refresh vim))) 2000))
            (is (= loader-text (slurp (io/file installed loader))) "the loader is untouched")
            (is (= "\" mine\n" (slurp foreign)))
            (is (= [] (filter #(.endsWith (.getName ^java.io.File %) ".tmp")
                              (file-seq installed)))
                "no temp file is left behind"))))
      (testing "deprovision still removes the install, and a later edit does not recreate it"
        (mount/teardown! host (:order report) {:deprovision (:deprovision p)})
        (is (not (.exists installed)))
        (is (not-any? #{:refreshed} (map :status (:installs (pack/refresh! config "hive.carto-flow.vim"
                                                                          (paths/plugin-dir (io/file dir "state"))
                                                                          [plugin] :test)))))
        (is (not (.exists installed))))
      (finally
        (mount/teardown! host (:order report))
        (t/delete-tree! dir)))))

(deftest an-absent-install-stays-absent
  (let [dir (t/temp-dir)
        home (io/file dir "home")
        root (runtime-root! (io/file dir "root"))
        ;; no record: the manifest's decls are used, so the install would be
        ;; found if it existed
        config (vim-config dir home root {:carto-flow.vim/host-record? false})
        {:keys [host report vim]} (mount-vim! config nil)]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (= [{:status :absent :runtime/id "hive-carto-flow" :dir (str (install-dir home))}]
             (mapv #(select-keys % [:status :runtime/id :dir]) (:installs (pack-refresh vim)))))
      (spit (io/file root plugin) "\" edited\n" :append true)
      (is (t/eventually #(= :watch (:trigger (pack-refresh vim))) 5000))
      (is (= [:absent] (mapv :status (:installs (pack-refresh vim)))))
      (is (not (.exists (io/file home ".vim"))) "nothing was created under home")
      (finally
        (mount/teardown! host (:order report))
        (t/delete-tree! dir)))))

(deftest a-directory-without-the-generated-loader-is-not-an-install
  (let [dir (t/temp-dir)
        home (io/file dir "home")
        installed (install-dir home)
        source (io/file dir "src")]
    (try
      (io/make-parents (io/file source plugin))
      (spit (io/file source plugin) "new\n")
      (io/make-parents (io/file installed plugin))
      (spit (io/file installed plugin) "old\n")
      (let [config {:carto-flow.vim/home (str home) :carto-flow.vim/host-record? false}]
        (is (= [:absent] (mapv :status (:installs (pack/refresh! config "hive.carto-flow.vim"
                                                                 source [plugin] :test)))))
        (is (= "old\n" (slurp (io/file installed plugin))))
        (spit (io/file installed loader) "\" Generated by hive-addon for runtime other\n")
        (is (= [:absent] (mapv :status (:installs (pack/refresh! config "hive.carto-flow.vim"
                                                                 source [plugin] :test))))
            "a loader generated for another runtime does not count"))
      (testing "a record naming no runtime for this addon refreshes nothing"
        (is (= [] (:installs (pack/refresh! {:carto-flow.vim/home (str home)
                                             :carto-flow.vim/installed (constantly {})}
                                            "hive.carto-flow.vim" source [plugin] :test)))))
      (finally (t/delete-tree! dir)))))

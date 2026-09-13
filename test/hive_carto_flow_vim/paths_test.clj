(ns hive-carto-flow-vim.paths-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hive-carto-flow-vim.paths :as paths]
            [hive-carto-flow-vim.test-support :as t]))

(deftest the-state-dir-prefers-config-then-xdg-then-home
  (is (= (io/file "/cfg")
         (paths/resolve-state-dir {:carto-flow.vim/state-dir "/cfg"} {"XDG_STATE_HOME" "/xdg"} "/home/u")))
  (is (= (io/file "/xdg/hive/carto-flow")
         (paths/resolve-state-dir {} {"XDG_STATE_HOME" "/xdg"} "/home/u")))
  (is (= (io/file "/home/u/.local/state/hive/carto-flow")
         (paths/resolve-state-dir {} {"XDG_STATE_HOME" ""} "/home/u"))))

(deftest the-port-file-is-only-deleted-by-the-server-it-names
  (let [dir (t/temp-dir)
        file (paths/port-file {} dir)]
    (try
      (paths/write-port-file! file 4242)
      (is (= 4242 (paths/read-port-file file)))
      (is (false? (paths/delete-port-file! file 1111)) "another server's port is kept")
      (is (.isFile file))
      (is (true? (paths/delete-port-file! file 4242)))
      (is (nil? (paths/read-port-file file)))
      (finally (t/delete-tree! dir)))))

(deftest plugin-extraction-writes-every-plugin-file-once
  (let [dir (t/temp-dir)]
    (try
      (let [root (paths/ensure-plugin-dir! dir)
            files (map #(io/file root %) paths/plugin-files)]
        (is (every? #(.isFile ^java.io.File %) files))
        (doseq [[rel f] (map vector paths/plugin-files files)]
          (is (= (slurp (io/resource (str "vim/" rel))) (slurp f))))
        (doseq [f files] (.setLastModified ^java.io.File f 1000))
        (is (= root (paths/ensure-plugin-dir! dir)))
        (is (every? #(= 1000 (.lastModified ^java.io.File %)) files)
            "an unchanged file is not rewritten")
        (spit (first files) "stale")
        (paths/ensure-plugin-dir! dir)
        (is (not= "stale" (slurp (first files))) "a drifted file is restored"))
      (finally (t/delete-tree! dir)))))

(deftest the-whole-hive-vessel-plugin-travels
  ;; hive-vessel's plugin is one vim9 entry point plus three autoload files
  ;; that import each other; a partial copy fails to load. Every file must be
  ;; on the classpath and land under the extracted runtime.
  (let [dir (t/temp-dir)]
    (try
      (let [root (paths/ensure-plugin-dir! dir)]
        (is (= #{"plugin/hive_vessel.vim"
                 "autoload/hive_vessel.vim"
                 "autoload/hive_vessel/ops.vim"
                 "autoload/hive_vessel/wire.vim"}
               (set paths/hive-vessel-files)))
        (doseq [rel paths/hive-vessel-files]
          (let [resource (io/resource (str paths/hive-vessel-resource-root rel))]
            (is (some? resource) (str rel " is on the classpath"))
            (is (= (slurp resource) (slurp (io/file root rel))) (str rel " is extracted")))))
      (finally (t/delete-tree! dir)))))

(deftest the-plugin-file-list-covers-the-vim-resource-tree
  (let [root (io/file "resources/vim")
        on-disk (->> (file-seq root)
                     (filter #(.isFile ^java.io.File %))
                     (map #(str (.relativize (.toPath root) (.toPath ^java.io.File %))))
                     set)]
    (is (= on-disk (set paths/plugin-files)))))

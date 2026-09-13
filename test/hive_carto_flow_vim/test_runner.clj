(ns hive-carto-flow-vim.test-runner
  (:require [clojure.test :as test]
            [hive-carto-flow-vim.paths-test]
            [hive-carto-flow-vim.vessel-test]
            [hive-carto-flow-vim.mount-test]
            [hive-carto-flow-vim.vim-integration-test]))

(def test-namespaces
  '[hive-carto-flow-vim.paths-test
    hive-carto-flow-vim.vessel-test
    hive-carto-flow-vim.mount-test
    hive-carto-flow-vim.vim-integration-test])

(defn -main
  [& _]
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))

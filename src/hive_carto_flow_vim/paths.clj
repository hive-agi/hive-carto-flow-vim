(ns hive-carto-flow-vim.paths
  "Filesystem locations shared with Vim: the state directory, the port file a
   Vim reads to find this addon's hive-vessel channel executor, and the
   extracted runtime directory (hive-vessel's channel plugin plus the
   carto_flow timeline plugin) a user adds to 'runtimepath'."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.file Files StandardCopyOption)))

(def port-file-name "vim.port")

(def plugin-dir-name "vim-plugin")

(def plugin-resource-root "vim/")

(def plugin-files
  "Every file of the carto_flow Vim plugin, relative to `plugin-resource-root`."
  ["plugin/carto_flow.vim"
   "autoload/carto_flow.vim"
   "syntax/cartoflow.vim"])

(def hive-vessel-resource-root "hive-vessel/vim/")

(def hive-vessel-files
  "hive-vessel's one Vim plugin, relative to `hive-vessel-resource-root`: the
   vim9 entry point, the panel painter the :vim-channel dialect calls, the
   HiveOp vocabulary, and the wire (discovery, hello, reconnection). The
   plugin cannot load from a partial copy, so every file of the directory
   travels."
  ["plugin/hive_vessel.vim"
   "autoload/hive_vessel.vim"
   "autoload/hive_vessel/ops.vim"
   "autoload/hive_vessel/wire.vim"])

(def runtime-sources
  "Classpath sources of the extracted runtime directory, as
   [[resource-root [relative-path ...]] ...]."
  [[hive-vessel-resource-root hive-vessel-files]
   [plugin-resource-root plugin-files]])

(defn resolve-state-dir
  "The carto-flow state directory: CONFIG's :carto-flow.vim/state-dir when set,
   else $XDG_STATE_HOME/hive/carto-flow, else HOME/.local/state/hive/carto-flow.
   ENV is a map of environment variables."
  ^File [config env home]
  (let [explicit (:carto-flow.vim/state-dir config)
        xdg (get env "XDG_STATE_HOME")]
    (cond
      (and explicit (not (str/blank? (str explicit)))) (io/file (str explicit))
      (and xdg (not (str/blank? xdg))) (io/file xdg "hive" "carto-flow")
      :else (io/file home ".local" "state" "hive" "carto-flow"))))

(defn state-dir
  "`resolve-state-dir` against the live environment."
  ^File [config]
  (resolve-state-dir config (System/getenv) (System/getProperty "user.home")))

(defn port-file
  "CONFIG's :carto-flow.vim/port-file when set, else DIR/vim.port."
  ^File [config dir]
  (let [explicit (:carto-flow.vim/port-file config)]
    (if (and explicit (not (str/blank? (str explicit))))
      (io/file (str explicit))
      (io/file dir port-file-name))))

(defn plugin-dir
  ^File [dir]
  (io/file dir plugin-dir-name))

(defn- write-atomically!
  [^File target ^String content]
  (io/make-parents target)
  (let [tmp (File/createTempFile (str "." (.getName target) ".") ".tmp" (.getParentFile target))]
    (try
      (spit tmp content)
      (Files/move (.toPath tmp) (.toPath target)
                  (into-array [StandardCopyOption/REPLACE_EXISTING
                               StandardCopyOption/ATOMIC_MOVE]))
      (finally
        (.delete tmp)))
    target))

(defn write-port-file!
  "Write PORT to FILE. Returns the file."
  ^File [file port]
  (write-atomically! (io/file file) (str port "\n")))

(defn read-port-file
  "The port recorded in FILE, or nil."
  [file]
  (let [f (io/file file)]
    (when (.isFile f)
      (parse-long (str/trim (slurp f))))))

(defn delete-port-file!
  "Delete FILE when it still records PORT, so a newer executor's file is never
   removed. Returns true when a file was deleted."
  [file port]
  (boolean (and (= port (read-port-file file))
                (.delete (io/file file)))))

(defn extract-runtime!
  "Extract SOURCES (see `runtime-sources`) from the classpath into ROOT,
   rewriting only files whose content differs. Returns ROOT's absolute path."
  [root sources]
  (let [root (io/file root)]
    (doseq [[resource-root rels] sources
            rel rels]
      (let [resource (or (io/resource (str resource-root rel))
                         (throw (ex-info "Vim runtime resource missing from classpath"
                                         {:resource (str resource-root rel)})))
            content (slurp resource)
            target (io/file root rel)]
        (when-not (and (.isFile target) (= content (slurp target)))
          (write-atomically! target content))))
    (.getAbsolutePath root)))

(defn ensure-plugin-dir!
  "Extract the full Vim runtime (hive-vessel's channel plugin and the carto_flow
   plugin) into DIR's plugin directory. Returns its absolute path."
  [dir]
  (extract-runtime! (plugin-dir dir) runtime-sources))

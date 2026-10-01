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

(defn- classpath-text
  [resource-root rel]
  (slurp (or (io/resource (str resource-root rel))
             (throw (ex-info "Vim runtime resource missing from classpath"
                             {:resource (str resource-root rel)})))))

(defn plugin-text
  "The text of carto_flow plugin file REL (see `plugin-files`): read from
   RUNTIME-ROOT, a directory laid out like resources/vim, when one is given,
   else from the classpath."
  [runtime-root rel]
  (if runtime-root
    (let [f (io/file runtime-root rel)]
      (if (.isFile f)
        (slurp f)
        (throw (ex-info "Vim runtime file missing from runtime root"
                        {:runtime-root (str runtime-root) :file rel}))))
    (classpath-text plugin-resource-root rel)))

(defn runtime-root
  "CONFIG's :carto-flow.vim/runtime-root, the directory the carto_flow plugin
   is read from instead of the classpath, or nil."
  [config]
  (let [explicit (:carto-flow.vim/runtime-root config)]
    (when (and explicit (not (str/blank? (str explicit))))
      (io/file (str explicit)))))

(defn- write-when-changed!
  [^File target ^String content]
  (when-not (and (.isFile target) (= content (slurp target)))
    (write-atomically! target content)))

(defn extract-runtime!
  "Extract SOURCES (see `runtime-sources`) from the classpath into ROOT,
   rewriting only files whose content differs. Returns ROOT's absolute path."
  [root sources]
  (let [root (io/file root)]
    (doseq [[resource-root rels] sources
            rel rels]
      (write-when-changed! (io/file root rel) (classpath-text resource-root rel)))
    (.getAbsolutePath root)))

(defn ensure-plugin-dir!
  "Extract the full Vim runtime (hive-vessel's channel plugin and the carto_flow
   plugin, the latter from RUNTIME-ROOT when given, see `plugin-text`) into
   DIR's plugin directory. Returns its absolute path."
  ([dir] (ensure-plugin-dir! dir nil))
  ([dir runtime-root]
   (let [root (plugin-dir dir)]
     (extract-runtime! root [[hive-vessel-resource-root hive-vessel-files]])
     (doseq [rel plugin-files]
       (write-when-changed! (io/file root rel) (plugin-text runtime-root rel)))
     (.getAbsolutePath root))))

(defn runtime-hash
  "Hex SHA-256 over the carto_flow plugin files as this classpath ships them,
   or as RUNTIME-ROOT holds them when given. A Vim that last loaded a runtime
   with another hash runs an older plugin."
  ([] (runtime-hash nil))
  ([runtime-root]
   (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
     (doseq [rel plugin-files]
       (.update digest (.getBytes (str rel "\n" (plugin-text runtime-root rel)) "UTF-8")))
     (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))))

(defn runtime-watchable?
  "Whether the carto_flow plugin can change under a running JVM: it is read
   from RUNTIME-ROOT, or at least one of its classpath resources is a real
   file (a source checkout, a :local/root dep). Jar-backed resources never
   change, so there is nothing to watch."
  [runtime-root]
  (boolean
   (or runtime-root
       (some #(= "file" (some-> (io/resource (str plugin-resource-root %)) .getProtocol))
             plugin-files))))

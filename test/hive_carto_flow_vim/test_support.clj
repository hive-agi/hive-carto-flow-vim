(ns hive-carto-flow-vim.test-support
  "A fake Vim speaking hive-vessel's channel protocol, plus temp dirs, polling
   and mount fixtures.

   hive-vessel's executor sends `[\"call\" fn args id]` with a negative id and
   BLOCKS for `[id, result]`, so a fake that only reads would hang every
   delivery. This one answers: the features probe (`call eval`) with whatever
   the fake was told to advertise, every other call with \"ok\"."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-addon.protocol :as addon]
            [hive-carto-flow-vim.channel :as channel]
            [hive.events.observer :as events-observer])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter)
           (java.net Socket SocketTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn temp-dir
  []
  (.toFile (Files/createTempDirectory "carto-flow-vim-test" (make-array FileAttribute 0))))

(defn delete-tree!
  [f]
  (let [f (io/file f)]
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-tree! child)))
    (.delete f)))

(defn eventually
  "Poll PRED every 10ms until truthy or TIMEOUT-MS elapses. Returns PRED's value."
  ([pred] (eventually pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (or (pred)
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 10)
             (recur)))))))

;; ---------------------------------------------------------------------------
;; Fake Vim
;; ---------------------------------------------------------------------------

(defn- reply-for
  [features [_ f args]]
  (cond
    (and (= "eval" f) (re-find #"carto_flow_features" (str (first args))))
    (vec (map #(if (keyword? %) (subs (str %) 1) (str %)) features))

    :else "ok"))

(defn fake-vim
  "Connect to PORT as Vim would and answer channel calls. Returns a handle whose
   :commands atom holds every decoded command, oldest first."
  ([port] (fake-vim port #{:carto-flow/timeline}))
  ([port features]
   (let [socket (Socket. "127.0.0.1" (int port))
         reader (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))
         writer (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)
         commands (atom [])
         running? (atom true)
         thread (doto (Thread.
                       ^Runnable
                       (fn []
                         (try
                           (while @running?
                             (if-let [line (.readLine reader)]
                               (let [command (json/read-str line)
                                     id (last command)]
                                 (swap! commands conj command)
                                 (when (number? id)
                                   (.write writer (str (json/write-str [id (reply-for features command)]) "\n"))
                                   (.flush writer)))
                               (reset! running? false)))
                           (catch Throwable _ nil)))
                       "fake-vim")
                  (.setDaemon true)
                  (.start))]
     {:socket socket :commands commands :running? running? :thread thread})))

(defn close-vim!
  [{:keys [^Socket socket running?]}]
  (reset! running? false)
  (try (.close socket) (catch Throwable _ nil)))

(defn calls-of
  "Every command calling FN, in order."
  [{:keys [commands]} f]
  (filterv #(and (vector? %) (= "call" (first %)) (= f (second %))) @commands))

(defn ingest-messages
  "The message argument of every carto_flow#ingest call, in order."
  [vim]
  (mapv #(first (nth % 2)) (calls-of vim "carto_flow#ingest")))

(defn vim-closed?
  [{:keys [^Socket socket]}]
  (eventually #(try (.setSoTimeout socket 50)
                    ;; -1 is end of stream: the peer closed the connection.
                    (neg? (.read (.getInputStream socket)))
                    (catch SocketTimeoutException _ false)
                    (catch java.io.IOException _ true))
              3000))

(defn live-acceptor-threads
  "Every live JVM thread belonging to this addon's accept loop."
  []
  (->> (keys (Thread/getAllStackTraces))
       (filter #(and (.isAlive ^Thread %)
                     (.startsWith (.getName ^Thread %) channel/thread-name)))
       vec))

(defn no-acceptor-threads?
  []
  (eventually #(empty? (live-acceptor-threads)) 3000))

;; ---------------------------------------------------------------------------
;; Mount fixtures
;; ---------------------------------------------------------------------------

(defrecord StubAddon [id hook-map]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _] {:success? true})
  (shutdown! [_] nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok})
  (excluded-tools [_] #{})
  (hooks [_] hook-map))

(defn carto-ctor
  [_]
  (->StubAddon "hive.carto" {}))

(def carto-spec
  {:addon/id "hive.carto"
   :addon/type :native
   :addon/init-ns "hive-carto-flow-vim.test-support"
   :addon/init-fn "carto-ctor"
   :addon/capabilities #{}})

(defn permit-only
  "A licence gate for this test's own addons: the gate's DIP swap point, not a
   bypass -- any other gated spec is still refused."
  [ids]
  (fn [spec]
    (when-not (contains? ids (:addon/id spec))
      :deny/not-under-test)))

(defn classpath-manifest
  [file-name]
  (edn/read-string
   (slurp (or (io/resource (str "META-INF/hive-addons/" file-name))
              (throw (ex-info "manifest not on classpath" {:file file-name}))))))

(defn mutate!
  [stage paths]
  (events-observer/notify!
   :carto/mutation
   {:coeffects {:event [:carto/mutation
                        {:operation :write-form
                         :stage stage
                         :paths paths}]}}))

(ns hive-carto-flow-vim.vessel
  "The Vim timeline's contribution to the hive-vessel action contract: pure
   data and pure fns.

   `{:op :carto-flow/frame ...}` lowers to `{:op :vim/call :fn
   \"carto_flow#ingest\" ...}` only on a `:vim-channel` target advertising
   `:carto-flow/timeline`. Any other target, including a Vim without the
   carto_flow plugin, falls back to core's generic panel translator
   (`hive-carto-flow.vessel/translators`), which hive-vessel lowers to
   hive_vessel#show_panel.

   The ingest message carries the JVM-side text projection (`line`, `detail`)
   next to the frame, so the plugin renders without re-deriving layout."
  (:require [clojure.string :as str]
            [hive-carto-flow.render.text :as text]
            [hive-carto-flow.vessel :as flow-vessel]
            [hive-vessel.core :as v]))

(def dialect :vim-channel)

(def timeline-feature
  "Feature a Vim advertises when the carto_flow plugin is loaded."
  :carto-flow/timeline)

(def ingest-fn "carto_flow#ingest")

(def hello-fn "carto_flow#hello")

(def server-name "hive.carto-flow.vim")

(defn frame-message
  "The Vim-side value for FRAME: index, phase, rendered timeline line, detail
   lines, and the frame itself (hive-vessel makes it JSON-safe)."
  [frame]
  {"index" (:frame/index frame)
   "phase" (some-> (text/phase-of frame) name)
   "line" (text/frame-line frame)
   "detail" (vec (text/detail-lines frame))
   "frame" frame})

(defn ingest-op
  "Translate a frame intent into a call of the plugin's ingest. The frame rides
   under :frame, or under :payload when the op came from an envelope."
  [{:keys [frame payload]} _target]
  {:op :vim/call :fn ingest-fn :args [(frame-message (or frame payload))]})

(def timeline-translator
  {:translator/id :hive.carto-flow.vim/frame->timeline
   :translator/op flow-vessel/op-type
   :translator/when {:vessel/dialect dialect
                     :vessel/features #{timeline-feature}}
   :translator/accepts flow-vessel/frame-accepts
   :translator/translate ingest-op})

(def translators
  "What this addon exposes under hive-vessel's `:vessel/translators` hook."
  [timeline-translator])

(defn registry
  "hive-vessel's standard registry plus core's generic frame translator plus the
   Vim timeline translator."
  []
  (v/standard-registry flow-vessel/translators translators))

(defn hello-op
  "Greeting that precedes a replay; FRAME-COUNT is the backlog size."
  [frame-count]
  {:op :vim/call :fn hello-fn :args [{"server" server-name "frames" frame-count}]})

(def seek-fn "carto_flow#seek")

(defn seek-op
  "Move the plugin's cursor to FRAME, the frame core's timeline cursor is on."
  [frame]
  {:op :vim/call :fn seek-fn :args [{"index" (:frame/index frame)}]})

(def seek-feature
  "Feature granted to a timeline Vim whose loaded script defines `seek-fn`:
   core's cursor moves are forwarded to it."
  :carto-flow/seek)

(def feature-fns
  "Each feature the server may grant: the Vim functions it entitles the server
   to call, and the advertised feature that makes it probe-able (`:within`). A
   feature is granted only when every one of its functions is defined in the
   connected Vim."
  {timeline-feature {:fns [ingest-fn hello-fn]}
   seek-feature {:fns [seek-fn] :within timeline-feature}})

(def probed-fns
  "Every function named in `feature-fns`, in a stable order."
  (vec (distinct (mapcat :fns (vals feature-fns)))))

(defn- vim-list
  "Vim list literal of the strings STRS."
  [strs]
  (str "[" (str/join ", " (map #(str "'" % "'") strs)) "]"))

(def features-expr
  "Vim expression answering the handshake: the features the Vim advertises
   (g:carto_flow_features, else what the loaded script derives through
   carto_flow#features()) and which of `probed-fns` are defined right now."
  (str "{'advertised': get(g:, 'carto_flow_features', "
       "exists('*carto_flow#features') ? carto_flow#features() : []), "
       "'defined': filter(" (vim-list probed-fns) ", 'exists(\"*\" . v:val)')}"))

(def features-probe-op
  "Handshake op: Vim's builtin eval of `features-expr`; its reply is a JSON
   object {\"advertised\": [feature ...], \"defined\": [fn ...]}."
  {:op :vim/call :fn "eval" :args [features-expr]})

(defn parse-features
  "Feature keywords from a decoded list of names (\"carto-flow/timeline\" ->
   :carto-flow/timeline). Anything that is not a list of strings is #{}."
  [values]
  (if (sequential? values)
    (into #{} (comp (filter string?) (remove str/blank?) (map keyword)) values)
    #{}))

(defn- vim-quote
  "Vim single-quoted string literal of S."
  [s]
  (str "'" (str/replace (str s) "'" "''") "'"))

(def runtime-sourced
  "What Vim's execute() answers `sync-op` with when it re-sourced the plugin;
   it answers \"\" when the plugin was already current."
  "reloaded")

(defn sync-op
  "Hot-reload op: Vim's builtin execute() re-sources the carto_flow plugin
   extracted under PLUGIN-DIR unless g:carto_flow_runtime already equals
   RUNTIME-HASH, then records the hash. The autoload script keeps its frames
   and connection across a re-source. A Vim that never loaded the plugin, or
   has g:carto_flow_no_sync set, is left alone."
  [plugin-dir runtime-hash]
  (let [source #(str "execute 'source ' . fnameescape(" (vim-quote (str plugin-dir "/" %)) ")")]
    {:op :vim/call
     :fn "execute"
     :args [[(str "if exists('g:loaded_carto_flow') && !get(g:, 'carto_flow_no_sync', 0)"
                  " && get(g:, 'carto_flow_runtime', '') !=# " (vim-quote runtime-hash))
             (source "autoload/carto_flow.vim")
             "unlet! g:loaded_carto_flow"
             (source "plugin/carto_flow.vim")
             (str "let g:carto_flow_runtime = " (vim-quote runtime-hash))
             (str "echon " (vim-quote runtime-sourced))
             "endif"]]}))

(defn confirm-features
  "The features to register for a Vim, from its decoded handshake REPLY. A
   feature in `feature-fns` is granted when it, or the feature it is `:within`,
   is advertised AND every one of its functions is defined; an advertised
   feature the table does not know passes through. A reply that is not the
   handshake object grants nothing."
  [reply]
  (if-not (map? reply)
    #{}
    (let [advertised (parse-features (get reply "advertised"))
          defined-fns (get reply "defined")
          defined (if (sequential? defined-fns) (set (filter string? defined-fns)) #{})
          granted? (fn [feature]
                     (let [{:keys [fns within]} (feature-fns feature)]
                       (and (or (contains? advertised feature)
                                (and within (contains? advertised within)))
                            (every? defined fns))))]
      (into (set (remove feature-fns advertised))
            (filter granted?)
            (keys feature-fns)))))

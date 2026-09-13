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

(def features-expr
  "Vim expression naming the features the connected Vim advertises. The
   carto_flow plugin sets g:carto_flow_features when it loads."
  "get(g:, 'carto_flow_features', [])")

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

(def features-probe-op
  "Handshake op: Vim's builtin eval of `features-expr`; its reply is the JSON
   list of advertised feature names."
  {:op :vim/call :fn "eval" :args [features-expr]})

(defn parse-features
  "Feature keywords from the decoded probe reply VALUES (\"carto-flow/timeline\"
   -> :carto-flow/timeline). Anything that is not a list of strings is #{}."
  [values]
  (if (sequential? values)
    (into #{} (comp (filter string?) (remove str/blank?) (map keyword)) values)
    #{}))

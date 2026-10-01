(ns hive-carto-flow-vim.addon
  "IAddon boundary for hive.carto-flow.vim, the Vim vessel extension of
   hive.carto-flow.

   Initialization starts this addon's hive-vessel :vim-channel executor (see
   `hive-carto-flow-vim.channel`), registers presenter `:vim` on the injected
   core instance as a `hive-carto-flow.extension/vessel-target` over that
   executor, writes the port file, and extracts the Vim runtime. Every Vim
   connection re-registers the presenter, so core replays the timeline to it.
   Shutdown reverses all of it."
  (:require [hive-addon.protocol :as addon]
            [hive-carto-flow.extension :as extension]
            [hive-carto-flow-vim.channel :as channel]
            [hive-carto-flow-vim.paths :as paths]
            [hive-carto-flow-vim.vessel :as vim-vessel]
            [hive-carto-flow-vim.watch :as watch]
            [hive-vessel.core :as v]
            [hive-carto-flow-vim.pack :as pack]))

(def addon-id-value "hive.carto-flow.vim")

(def presenter-id :vim)

(def default-port 0)

(defn delivery-target
  "A fresh presentation target for core: each envelope is dispatched through
   REGISTRY to the vessel RESOLVE-VESSEL returns at delivery time. A nil vessel
   or a failed dispatch throws, which core reads as degraded."
  [registry resolve-vessel]
  (extension/vessel-target {:dispatch! v/dispatch!
                            :registry registry
                            :vessel resolve-vessel}))

(defn cursor-follower
  "A core cursor listener: when core's timeline cursor moves (next!, previous!,
   latest! from any client), put the connected Vim's timeline cursor on the same
   frame. Sends run in order on OUTBOX, an agent, so a slow or absent Vim never
   blocks whoever moved the cursor. A Vim without the timeline plugin, or none at
   all, is left alone, and a failed send is dropped: the cursor is a view, and
   the next move sends it again."
  [registry resolve-vessel outbox]
  (fn [frame _snapshot]
    (send-off outbox
              (fn [sent]
                (try
                  (let [vessel (resolve-vessel)]
                    (if (contains? (:vessel/features vessel) vim-vessel/timeline-feature)
                      (do (v/dispatch! registry vessel (vim-vessel/seek-op frame))
                          (inc sent))
                      sent))
                  (catch Throwable _
                    sent))))))

(defn runtime-sync
  "The channel's per-connection sync step: extract this classpath's Vim runtime
   into DIR and have the connected Vim re-source its carto_flow plugin from
   there when the one it runs is not this runtime. A Vim reconnecting after a
   hot reload of this addon so picks up the new plugin without a restart.
   RUNTIME-ROOT, when given, replaces the classpath as the plugin's source (see
   `paths/plugin-text`).
   Returns {:hash h :reloaded? bool}, or {:hash h :error e}."
  ([dir] (runtime-sync dir nil))
  ([dir runtime-root]
   (fn [registry target]
     (let [plugin-dir (paths/ensure-plugin-dir! dir runtime-root)
           hash (paths/runtime-hash runtime-root)
           result (v/dispatch! registry target (vim-vessel/sync-op plugin-dir hash))]
       (if-let [results (get-in result [:ok :plan/results])]
         {:hash hash
          :reloaded? (= vim-vessel/runtime-sourced (channel/decode-reply (first results)))}
         {:hash hash :error (:error result)})))))

(defn pack-refresher
  "A (fn [trigger]) that extracts the Vim runtime into DIR, then rewrites the
   changed files of the install hive-addon's runtime provisioner made from it
   (see `hive-carto-flow-vim.pack`), so a Vim started next loads the current
   plugin. An absent install stays absent. The report lands in STATE under
   :pack-refresh and is returned. Never throws."
  [state config dir]
  (fn [trigger]
    (let [report (try
                   (pack/refresh! config addon-id-value
                                  (paths/ensure-plugin-dir! dir (paths/runtime-root config))
                                  (into (vec paths/hive-vessel-files) paths/plugin-files)
                                  trigger)
                   (catch Throwable t
                     {:trigger trigger :at (java.util.Date.)
                      :installs [{:status :error :error (or (ex-message t) (str t))}]}))]
      (swap! state assoc :pack-refresh report)
      report)))

(defn runtime-watch
  "Start the watch that pushes plugin edits into the connected Vim: when the
   carto_flow plugin's hash changes, refresh the provisioned install through
   REFRESH-PACK! (fn [trigger]), when given, so a Vim started next loads the
   new plugin too, then re-run the channel's sync over the live connection
   (`channel/resync!`). Returns nil, without a thread, when the plugin cannot
   change (jar-backed and no runtime root) or CONFIG sets
   :carto-flow.vim/watch-runtime? false."
  ([config ch] (runtime-watch config ch nil))
  ([config ch refresh-pack!]
   (let [root (paths/runtime-root config)]
     (when (and (not (false? (:carto-flow.vim/watch-runtime? config)))
                (paths/runtime-watchable? root))
       (watch/start! {:hash-fn #(paths/runtime-hash root)
                      :interval-ms (:carto-flow.vim/watch-interval-ms config)
                      :on-change (fn [_hash]
                                   (when refresh-pack! (refresh-pack! :watch))
                                   (channel/resync! ch :watch))})))))

(defn- on-connect
  "Bring a freshly connected Vim to the timeline: greet it when it runs the
   timeline plugin, then re-register the presenter so core replays the backlog
   in order and continues live."
  [config registry]
  (fn [ch vessel]
    (when (contains? (:vessel/features vessel) vim-vessel/timeline-feature)
      (v/dispatch! registry vessel
                   (vim-vessel/hello-op (count (:timeline/frames (extension/snapshot config))))))
    (extension/register! config presenter-id
                         (delivery-target registry #(channel/vessel ch)))))

(defn- release!
  "Undo whatever of a start got done. Never throws."
  [config ch port-file runtime-watch]
  (when runtime-watch
    (try (watch/stop! runtime-watch) (catch Throwable _ nil)))
  (try (extension/unsubscribe-cursor! config presenter-id) (catch Throwable _ nil))
  (when ch
    (try (channel/stop! ch) (catch Throwable _ nil)))
  (try (extension/unregister! config presenter-id) (catch Throwable _ nil))
  (when (and ch port-file)
    (try (paths/delete-port-file! port-file (channel/port ch)) (catch Throwable _ nil))))

(defn- start!
  [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (let [config (merge seed runtime-config)
            dir (paths/state-dir config)
            port-file (paths/port-file config dir)
            ch-ref (volatile! nil)
            watch-ref (volatile! nil)]
        (try
          (when-not (extension/core-addon config)
            (throw (ex-info "hive.carto-flow is not injected under :mount/dependencies" {})))
          (let [registry (vim-vessel/registry)
                ch (channel/start! {:port (or (:carto-flow.vim/port config) default-port)
                                    :registry registry
                                    :call-timeout-ms (:carto-flow.vim/call-timeout-ms config)
                                    :probe-timeout-ms (:carto-flow.vim/probe-timeout-ms config)
                                    :sync! (runtime-sync dir (paths/runtime-root config))
                                    :on-connect (on-connect config registry)})]
            (vreset! ch-ref ch)
            (when-not (extension/register! config presenter-id
                                           (delivery-target registry #(channel/vessel ch)))
              (throw (ex-info "hive.carto-flow refused the :vim presenter (core not active)" {})))
            (extension/subscribe-cursor! config presenter-id
                                         (cursor-follower registry #(channel/vessel ch) (agent 0)))
            (paths/write-port-file! port-file (channel/port ch))
            (let [plugin (try {:plugin-dir (paths/ensure-plugin-dir! dir (paths/runtime-root config))}
                              (catch Throwable t {:plugin-error (ex-message t)}))
                  refresh-pack! (pack-refresher state config dir)]
              (reset! state (merge {:lifecycle :active
                                    :config config
                                    :channel ch
                                    :state-dir dir
                                    :port-file port-file}
                                   plugin))
              (when-not (false? (:carto-flow.vim/refresh-pack? config))
                (refresh-pack! :initialize))
              (let [rw (vreset! watch-ref
                                (runtime-watch config ch
                                               (when-not (false? (:carto-flow.vim/refresh-pack? config))
                                                 refresh-pack!)))]
                (swap! state assoc :runtime-watch rw))
              {:success? true
               :metadata (merge {:port (channel/port ch)
                                 :port-file (str port-file)}
                                plugin
                                (when-let [r (:pack-refresh @state)]
                                  {:pack-refresh r}))}))
          (catch Throwable t
            (release! config @ch-ref port-file @watch-ref)
            (reset! state {:lifecycle :failed :last-error (ex-message t)})
            {:success? false :errors [(or (ex-message t) (str t))]}))))))

(defn- stop!
  [state]
  (locking state
    (let [{:keys [lifecycle config channel port-file runtime-watch]} @state]
      (when (= :active lifecycle)
        (release! config channel port-file runtime-watch))
      (reset! state {:lifecycle :stopped})
      nil)))

(defrecord CartoFlowVimAddon [state seed]
  addon/IAddon

  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:carto-flow-presenter :health-reporting})

  (initialize! [_ runtime-config]
    (start! state seed runtime-config))

  (shutdown! [_]
    (stop! state))

  (tools [_] [])
  (schema-extensions [_] [])

  (health [_]
    (let [{:keys [lifecycle config channel port-file plugin-dir plugin-error last-error
                  runtime-watch pack-refresh]} @state
          executor (when channel (channel/status channel))
          listening? (boolean (:listening? executor))
          presenter (if (= :active lifecycle)
                      (extension/presenter-status config presenter-id)
                      :detached)]
      {:status (cond
                 (= :failed lifecycle) :down
                 (not= :active lifecycle) :degraded
                 (not listening?) :down
                 (= :degraded presenter) :degraded
                 :else :ok)
       :details (cond-> {:lifecycle lifecycle
                         :listening? listening?
                         :presenter presenter}
                  executor (merge (dissoc executor :listening?))
                  port-file (assoc :port-file (str port-file))
                  plugin-dir (assoc :plugin-dir plugin-dir)
                  runtime-watch (assoc :runtime-watch (watch/status runtime-watch))
                  pack-refresh (assoc :pack-refresh pack-refresh)
                  plugin-error (assoc :plugin-error plugin-error)
                  last-error (assoc :last-error last-error))}))

  (excluded-tools [_] #{})

  (hooks [_]
    (let [{:keys [lifecycle config channel state-dir port-file]} @state]
      (if (= :active lifecycle)
        {:carto-flow.vim/port #(channel/port channel)
         :carto-flow.vim/port-file #(str port-file)
         :carto-flow.vim/plugin-dir #(paths/ensure-plugin-dir! state-dir (paths/runtime-root config))
         :carto-flow.vim/vessel #(channel/vessel channel)
         v/hook-key vim-vessel/translators}
        {}))))

(defn addon-ctor
  "Pure constructor resolved from the mount manifest."
  [config]
  (->CartoFlowVimAddon (atom {:lifecycle :created}) (or config {})))

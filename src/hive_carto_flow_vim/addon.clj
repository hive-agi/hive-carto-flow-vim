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
            [hive-vessel.core :as v]))

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
  [config ch port-file]
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
            ch-ref (volatile! nil)]
        (try
          (when-not (extension/core-addon config)
            (throw (ex-info "hive.carto-flow is not injected under :mount/dependencies" {})))
          (let [registry (vim-vessel/registry)
                ch (channel/start! {:port (or (:carto-flow.vim/port config) default-port)
                                    :registry registry
                                    :call-timeout-ms (:carto-flow.vim/call-timeout-ms config)
                                    :probe-timeout-ms (:carto-flow.vim/probe-timeout-ms config)
                                    :on-connect (on-connect config registry)})]
            (vreset! ch-ref ch)
            (when-not (extension/register! config presenter-id
                                           (delivery-target registry #(channel/vessel ch)))
              (throw (ex-info "hive.carto-flow refused the :vim presenter (core not active)" {})))
            (paths/write-port-file! port-file (channel/port ch))
            (let [plugin (try {:plugin-dir (paths/ensure-plugin-dir! dir)}
                              (catch Throwable t {:plugin-error (ex-message t)}))]
              (reset! state (merge {:lifecycle :active
                                    :config config
                                    :channel ch
                                    :state-dir dir
                                    :port-file port-file}
                                   plugin))
              {:success? true
               :metadata (merge {:port (channel/port ch)
                                 :port-file (str port-file)}
                                plugin)}))
          (catch Throwable t
            (release! config @ch-ref port-file)
            (reset! state {:lifecycle :failed :last-error (ex-message t)})
            {:success? false :errors [(or (ex-message t) (str t))]}))))))

(defn- stop!
  [state]
  (locking state
    (let [{:keys [lifecycle config channel port-file]} @state]
      (when (= :active lifecycle)
        (release! config channel port-file))
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
    (let [{:keys [lifecycle config channel port-file plugin-dir plugin-error last-error]} @state
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
                  plugin-error (assoc :plugin-error plugin-error)
                  last-error (assoc :last-error last-error))}))

  (excluded-tools [_] #{})

  (hooks [_]
    (let [{:keys [lifecycle channel state-dir port-file]} @state]
      (if (= :active lifecycle)
        {:carto-flow.vim/port #(channel/port channel)
         :carto-flow.vim/port-file #(str port-file)
         :carto-flow.vim/plugin-dir #(paths/ensure-plugin-dir! state-dir)
         :carto-flow.vim/vessel #(channel/vessel channel)
         v/hook-key vim-vessel/translators}
        {}))))

(defn addon-ctor
  "Pure constructor resolved from the mount manifest."
  [config]
  (->CartoFlowVimAddon (atom {:lifecycle :created}) (or config {})))

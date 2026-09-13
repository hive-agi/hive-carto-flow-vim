(ns hive-carto-flow-vim.provision-test
  "The Vim runtime provisioned at injection: mounting hive.carto-flow.vim with
   hive-addon's runtime provisioner installs the plugin where Vim autoloads it,
   and a Vim started afterwards, with no command typed, connects, opens the
   timeline, follows the edited line and pages frames from the detail."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [hive-addon.runtime.boundary :as runtime]
            [hive-carto-flow-vim.test-support :as t]
            [hive.events.observer :as events-observer]
            [clojure.java.shell :as sh]
            [hive-carto-flow-vim.paths :as paths])
  (:import))

(def ^:private gated-ids #{"hive.carto-flow" "hive.carto-flow.vim"})

(defn- mount-with-provisioner!
  [state-dir home]
  (let [p (runtime/provisioner {:home (str home) :live? false})
        host (mount/atom-mount-host)
        specs [t/carto-spec
               (t/classpath-manifest "hive-carto-flow.edn")
               (update (t/classpath-manifest "hive-carto-flow-vim.edn") :addon/config merge
                       {:carto-flow.vim/state-dir (str state-dir)})]
        report (mount/mount! (mount/solve specs) host
                             {:license-gate (t/permit-only gated-ids)
                              :provision (:provision p)})]
    {:host host :report report :provisioner p}))

(defn- result-for
  [report id]
  (some #(when (= id (:addon/id %)) %) (:mounted report)))

(defn- install-dir
  [home]
  (io/file home ".vim" "pack" "hive" "start" "hive-carto-flow"))

(deftest injection-installs-the-runtime-where-vim-autoloads-it
  (let [dir (t/temp-dir)
        home (io/file dir "home")
        {:keys [host report provisioner]} (mount-with-provisioner! (io/file dir "state") home)
        vim-ext (mount-port/registered host "hive.carto-flow.vim")
        installed (install-dir home)]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (let [runtime-report (:runtime (result-for report "hive.carto-flow.vim"))]
        (is (true? (:ok? runtime-report)) (pr-str runtime-report))
        (is (= ["hive-carto-flow"] (mapv :runtime/id (:runtimes runtime-report)))))
      (testing "the runtime is the extracted plugin directory, as Vim autoloads it"
        (doseq [rel (concat paths/plugin-files paths/hive-vessel-files)]
          (is (.isFile (io/file installed rel)) rel)))
      (testing "the loader is bound to this addon's live port file and connects"
        (let [loader (slurp (io/file installed "plugin/zz_hive_runtime.vim"))
              port-file ((:carto-flow.vim/port-file (addon/hooks vim-ext)))]
          (is (str/includes? loader (str "let g:carto_flow_port_file = '" port-file "'")))
          (is (str/includes? loader "let g:carto_flow_auto_open = 1"))
          (is (str/includes? loader "CartoFlowConnect"))
          (is (= ((:carto-flow.vim/port (addon/hooks vim-ext)))
                 (parse-long (str/trim (slurp port-file))))
              "the bound file names the executor's port")))
      (testing "teardown removes the runtime"
        (mount/teardown! host (:order report) {:deprovision (:deprovision provisioner)})
        (is (not (.exists installed))))
      (finally
        (mount/teardown! host (:order report))
        (t/delete-tree! dir)))))

;; ---------------------------------------------------------------------------
;; A real Vim, started after injection, with nothing typed
;; ---------------------------------------------------------------------------

(defn- vim-in-tmux?
  "vim with the features the runtime needs, and tmux to give it a terminal:
   without one Vim never reaches VimEnter, which is what a started client does."
  []
  (let [{:keys [exit out]} (sh/sh "sh" "-c" "command -v tmux >/dev/null && vim --version")]
    (and (zero? exit)
         (every? #(str/includes? out %) ["+channel" "+timers" "+packages"]))))

(defn- ask-vim!
  "Press KEYS in the Vim in tmux SESSION once, then keep asking it for
   [bufname line first-line] until DONE? holds for that answer (20s). Returns
   the last complete answer. Polling instead of sleeping keeps the test
   correct under CPU contention."
  [session file done? & keys]
  (when (seq keys)
    (apply sh/sh "tmux" "send-keys" "-t" session keys))
  (let [complete #(let [f (io/file file)]
                    (when (.isFile f)
                      (let [lines (str/split (slurp f) #"\n" -1)]
                        (when (>= (count lines) 3) (vec (take 3 lines))))))
        last-answer (atom nil)]
    (or (t/eventually
         #(do (Thread/sleep 200)
              (.delete (io/file file))
              (sh/sh "tmux" "send-keys" "-t" session
                     (str ":call writefile([bufname('%'), line('.'), getline(1)], '" file "')")
                     "Enter")
              (when-let [answer (t/eventually complete 3000)]
                (reset! last-answer answer)
                (when (done? answer) answer)))
         20000)
        @last-answer)))

(defn- vim-state!
  "Poll the Vim in SESSION until it holds at least FRAMES frames. Returns
   {:frames :timeline-window? :focus :code-line}, or the last answer on timeout.
   Asking goes through keys, so it also flushes any pending redraw."
  [session file frames]
  (let [complete #(let [f (io/file file)]
                    (when (.isFile f)
                      (let [lines (str/split (slurp f) #"\n" -1)]
                        (when (>= (count lines) 4) lines))))
        ask (fn []
              (.delete (io/file file))
              (sh/sh "tmux" "send-keys" "-t" session "Escape"
                     (str ":call writefile([carto_flow#status().frames,"
                          " bufwinid(bufnr('carto-flow://timeline')) != -1, bufname('%'),"
                          " line('.', bufwinid(bufnr('src/a.clj')))], '" file "')")
                     "Enter")
              (when-let [[n tw focus line] (t/eventually complete 3000)]
                {:frames (parse-long n) :timeline-window? (= "1" tw)
                 :focus focus :code-line (parse-long line)}))
        last-answer (atom nil)]
    (or (t/eventually #(let [a (ask)]
                         (reset! last-answer a)
                         (when (and a (>= (:frames a) frames)) a))
                      20000)
        @last-answer)))

(defn- fact!
  [event-id payload]
  (events-observer/notify! event-id {:coeffects {:event [event-id payload]}}))

(defn- start-vim-in-tmux!
  [session workspace home file]
  (sh/sh "tmux" "new-session" "-d" "-s" session "-x" "160" "-y" "45" "-c" (str workspace)
         (str "env HOME='" home "' TERM=xterm vim -N -u NORC -i NONE"
              " --cmd 'set packpath=" home "/.vim' " file)))

(deftest a-vim-started-after-injection-connects-follows-and-pages-by-itself
  (if-not (vim-in-tmux?)
    (println "SKIP provision vim test: needs vim +channel +timers +packages and tmux")
    (let [dir (t/temp-dir)
          home (io/file dir "home")
          workspace (io/file dir "ws")
          answer (str (io/file dir "answer.txt"))
          session (str "cf-provision-" (System/nanoTime))
          {:keys [host report]} (mount-with-provisioner! (io/file dir "state") home)
          vim-ext (mount-port/registered host "hive.carto-flow.vim")
          attached? #(true? (:vim-attached? (:details (addon/health vim-ext))))
          shows (fn [heading] (fn [[buf _ first-line]] (and (= "carto-flow://frame" buf) (= heading first-line))))]
      (try
        (is (:ok? report) (pr-str (:mounted report)))
        (io/make-parents (io/file workspace "src" "a.clj"))
        (spit (io/file workspace "src" "a.clj") "(ns a)\n\n(defn f [])\n(defn g [])\n(defn h [])\n")
        (start-vim-in-tmux! session workspace home "src/a.clj")
        (is (t/eventually attached? 30000) "the Vim connected with nothing typed")
        (let [base {:correlation/id "op-1" :op/id "op-1" :op/command "write-form"
                    :scope "ws" :actor/id "carto" :paths ["src/a.clj"]}]
          (fact! :carto/operation-started (merge base {:stage :apply :status :running}))
          (fact! :carto/operation-completed
                 (merge base {:stage :succeeded :status :succeeded
                              :diff "--- a/src/a.clj\n+++ b/src/a.clj\n@@ -3,1 +4,2 @@\n (defn g [])\n+(defn h [])\n"})))
        (let [state (vim-state! session answer 2)]
          (is (= 2 (:frames state)) (pr-str state))
          (is (true? (:timeline-window? state)) "auto-open showed the timeline")
          (is (= "src/a.clj" (:focus state)) "focus stayed in the code window")
          (is (= 5 (:code-line state)) "the live frame moved the code to the diff's first changed line"))
        (is (= "frame #1  succeeded  write-form"
               (last (ask-vim! session answer (shows "frame #1  succeeded  write-form") "C-w" "k" "Enter")))
            "<CR> on the timeline opened the latest frame's detail")
        (is (= "frame #0  apply  write-form"
               (last (ask-vim! session answer (shows "frame #0  apply  write-form") "p")))
            "p in the detail paged back and re-rendered in place")
        (is (= "frame #1  succeeded  write-form"
               (last (ask-vim! session answer (shows "frame #1  succeeded  write-form") "n")))
            "n in the detail paged forward")
        (finally
          (sh/sh "tmux" "kill-session" "-t" session)
          (mount/teardown! host (:order report))
          (t/delete-tree! dir))))))

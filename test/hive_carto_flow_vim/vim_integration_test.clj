(ns hive-carto-flow-vim.vim-integration-test
  "A real headless Vim runs the shipped plugin against a mounted
   hive.carto-flow.vim. Skipped when /usr/bin/vim is absent or lacks +channel."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [hive-carto-flow.render.text :as text]
            [hive-carto-flow-vim.test-support :as t])
  (:import (java.lang ProcessBuilder$Redirect)
           (java.util.concurrent TimeUnit)))

(def vim-path "/usr/bin/vim")

(def vim-timeout-seconds 60)

(defn- vim-with-channels?
  []
  (and (.canExecute (io/file vim-path))
       (let [p (.start (doto (ProcessBuilder. [vim-path "--version"])
                         (.redirectErrorStream true)))
             out (slurp (.getInputStream p))]
         (.waitFor p 10 TimeUnit/SECONDS)
         (and (str/includes? out "+channel") (str/includes? out "+timers")))))

(defn- vim-string
  [s]
  (str "'" (str/replace (str s) "'" "''") "'"))

(defn- script
  "Vim script: open the timeline, wait for two frames, dump buffers to
   out1/detail, then wait for a reconnect replay and dump out2."
  [{:keys [plugin-dir port-file out1 detail out2 log]}]
  (str/join
   "\n"
   [(str "let g:carto_flow_port_file = " (vim-string port-file))
    "let g:carto_flow_reconnect_ms = 50"
    (str "call ch_logfile(" (vim-string log) ", 'w')")
    (str "execute 'set rtp^=' . fnameescape(" (vim-string plugin-dir) ")")
    ;; carto_flow opens its own JSON channel to the executor; hive-vessel's
    ;; autoload script in the same runtime only paints the fallback panel.
    "runtime plugin/carto_flow.vim"
    "function! s:wait(cond, seconds) abort"
    "  let l:start = reltime()"
    "  while !eval(a:cond) && reltimefloat(reltime(l:start)) < a:seconds"
    "    sleep 20m"
    "  endwhile"
    "endfunction"
    "CartoFlow"
    "call s:wait('len(carto_flow#frames()) >= 2', 30.0)"
    (str "call writefile(getline(1, '$'), " (vim-string out1) ")")
    "let s:timeline = win_getid()"
    "call carto_flow#detail()"
    (str "call writefile(getline(1, '$') + ['filetype=' . &filetype], " (vim-string detail) ")")
    "call win_gotoid(s:timeline)"
    "call s:wait('carto_flow#status().received >= 4 && carto_flow#connected()', 30.0)"
    (str "call writefile(getline(1, '$') + [string(carto_flow#status())], " (vim-string out2) ")")
    "qa!"
    ""]))

(defn- lines-of
  [f]
  (when (.isFile (io/file f))
    (str/split-lines (slurp f))))

(deftest a-headless-vim-renders-the-timeline-it-receives-over-the-channel
  (if-not (vim-with-channels?)
    (println "SKIP vim integration: no" vim-path "with +channel +timers")
    (let [dir (t/temp-dir)
          host (mount/atom-mount-host)
          report (mount/mount!
                  (mount/solve [t/carto-spec
                                (t/classpath-manifest "hive-carto-flow.edn")
                                (update (t/classpath-manifest "hive-carto-flow-vim.edn")
                                        :addon/config merge
                                        {:carto-flow.vim/state-dir (str dir)})])
                  host
                  {:license-gate (t/permit-only #{"hive.carto-flow" "hive.carto-flow.vim"})})
          vim (mount-port/registered host "hive.carto-flow.vim")
          flow (mount-port/registered host "hive.carto-flow")
          files {:plugin-dir ((:carto-flow.vim/plugin-dir (addon/hooks vim)))
                 :port-file (str (io/file dir "vim.port"))
                 :out1 (str (io/file dir "out1.txt"))
                 :detail (str (io/file dir "detail.txt"))
                 :out2 (str (io/file dir "out2.txt"))
                 :log (str (io/file dir "channel.log"))}
          script-file (io/file dir "run.vim")
          _ (spit script-file (script files))
          frames #(:timeline/frames ((:carto-flow/timeline (addon/hooks flow))))
          clients #(if (true? (:vim-attached? (:details (addon/health vim)))) 1 0)
          process (atom nil)]
      (try
        (is (:ok? report) (pr-str (:mounted report)))
        (t/mutate! :carto.mutation/intent ["src/a.clj"])
        (reset! process
                (.start (doto (ProcessBuilder. [vim-path "-N" "-u" "NONE" "-i" "NONE" "-es"
                                                "-S" (str script-file)])
                          (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null")))
                          (.redirectErrorStream true)
                          (.redirectOutput (io/file dir "vim.out")))))
        (is (t/eventually #(= 1 (clients)) 20000) "vim connected")
        (t/mutate! :carto.mutation/succeeded ["src/a.clj" "src/b.clj"])
        (is (t/eventually #(some #{"filetype=diff"} (lines-of (:detail files))) 30000)
            "vim rendered the timeline and the detail")
        (let [out1 (lines-of (:out1 files))
              [f0 f1] (frames)]
          (println "vim timeline buffer:\n" (str/join "\n" out1))
          (is (= "Carto Flow -- this session's Carto changes" (first out1)))
          (is (str/starts-with? (second out1) "2 frames  [connected 127.0.0.1:"))
          (is (some #{(str "  " (text/frame-line f0))} out1) "the replayed backlog frame")
          (is (= (str "> " (text/frame-line f1)) (last out1)) "the live frame, under the cursor")
          (is (= (conj (vec (text/detail-lines f1)) "filetype=diff")
                 (lines-of (:detail files)))
              "<CR> detail shows core's detail lines as a diff buffer"))
        (addon/shutdown! vim)
        (is (= {:success? true}
               (select-keys (addon/initialize! vim {:mount/dependencies {"hive.carto-flow" flow}
                                                    :carto-flow.vim/state-dir (str dir)})
                            [:success?])))
        (is (.waitFor ^Process @process vim-timeout-seconds TimeUnit/SECONDS) "vim exited in time")
        (let [out2 (lines-of (:out2 files))]
          (println "vim after reconnect:\n" (str/join "\n" out2))
          (is (str/starts-with? (nth out2 1) "2 frames  [connected 127.0.0.1:")
              "the timer reconnected to the restarted server and the replay rebuilt the view")
          (is (= (str "> " (text/frame-line (second (frames)))) (nth out2 4))))
        (finally
          (when-let [^Process p @process]
            (when (.isAlive p)
              (println "vim output:" (slurp (io/file dir "vim.out")))
              (println "channel log:" (some-> (io/file (:log files)) (#(when (.isFile %) (slurp %)))))
              (.destroyForcibly p)))
          (when vim (addon/shutdown! vim))
          (when flow (addon/shutdown! flow))
          (t/delete-tree! dir))))))

(deftest cartoflowfollow-flips-sets-and-refuses-unknown-arguments
  (if-not (vim-with-channels?)
    (println "SKIP vim integration: no" vim-path "with +channel +timers")
    (let [dir        (t/temp-dir)
          plugin-dir (-> (io/resource "vim/plugin/carto_flow.vim") io/file
                         .getParentFile .getParentFile str)
          out        (io/file dir "follow.out")
          script     (io/file dir "follow.vim")]
      (try
        (spit script
              (str/join
               "\n"
               [(str "execute 'set rtp^=' . fnameescape(" (vim-string plugin-dir) ")")
                "runtime plugin/carto_flow.vim"
                "let s:out = [get(g:, 'carto_flow_follow_edits', 1)]"
                "CartoFlowFollow"
                "call add(s:out, g:carto_flow_follow_edits)"
                "CartoFlowFollow"
                "call add(s:out, g:carto_flow_follow_edits)"
                "CartoFlowFollow off"
                "call add(s:out, g:carto_flow_follow_edits)"
                "CartoFlowFollow on"
                "call add(s:out, g:carto_flow_follow_edits)"
                "silent! CartoFlowFollow maybe"
                "call add(s:out, g:carto_flow_follow_edits)"
                "call add(s:out, join(carto_flow#follow_complete('o', '', 0), ','))"
                (str "call writefile(s:out, " (vim-string (str out)) ")")
                "qa!"
                ""]))
        (let [p (.start (doto (ProcessBuilder. [vim-path "-N" "-u" "NONE" "-i" "NONE" "-es"
                                                "-S" (str script)])
                          (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null")))
                          (.redirectErrorStream true)
                          (.redirectOutput (io/file dir "vim.out"))))]
          (is (.waitFor p vim-timeout-seconds TimeUnit/SECONDS) "vim exited in time")
          (is (= ["1" "0" "1" "0" "1" "1" "on,off"] (lines-of out))
              "default on; bare flips twice; off and on set; an unknown argument changes nothing"))
        (finally
          (t/delete-tree! dir))))))

(deftest a-cursor-move-in-core-moves-the-vim-cursor
  (if-not (vim-with-channels?)
    (println "SKIP vim integration: no" vim-path "with +channel +timers")
    (let [dir (t/temp-dir)
          host (mount/atom-mount-host)
          report (mount/mount!
                  (mount/solve [t/carto-spec
                                (t/classpath-manifest "hive-carto-flow.edn")
                                (update (t/classpath-manifest "hive-carto-flow-vim.edn")
                                        :addon/config merge
                                        {:carto-flow.vim/state-dir (str dir)})])
                  host
                  {:license-gate (t/permit-only #{"hive.carto-flow" "hive.carto-flow.vim"})})
          vim (mount-port/registered host "hive.carto-flow.vim")
          flow (mount-port/registered host "hive.carto-flow")
          out (fn [name] (str (io/file dir name)))
          script-file (io/file dir "cursor.vim")
          process (atom nil)]
      (try
        (is (:ok? report) (pr-str (:mounted report)))
        (t/mutate! :carto.mutation/intent ["src/a.clj"])
        (t/mutate! :carto.mutation/succeeded ["src/a.clj"])
        (spit script-file
              (str/join
               "\n"
               [(str "let g:carto_flow_port_file = " (vim-string (out "vim.port")))
                "let g:carto_flow_reconnect_ms = 50"
                "let g:carto_flow_follow_edits = 0"
                (str "execute 'set rtp^=' . fnameescape("
                     (vim-string ((:carto-flow.vim/plugin-dir (addon/hooks vim)))) ")")
                "runtime plugin/carto_flow.vim"
                "function! s:wait(cond, seconds) abort"
                "  let l:start = reltime()"
                "  while !eval(a:cond) && reltimefloat(reltime(l:start)) < a:seconds"
                "    sleep 20m"
                "  endwhile"
                "endfunction"
                "CartoFlow"
                "call s:wait('len(carto_flow#frames()) >= 2', 30.0)"
                (str "call writefile(['ready'], " (vim-string (out "ready")) ")")
                "call s:wait('carto_flow#status().cursor == 0', 30.0)"
                (str "call writefile([carto_flow#status().cursor], " (vim-string (out "moved-back")) ")")
                "call s:wait('carto_flow#status().cursor == 1', 30.0)"
                (str "call writefile([carto_flow#status().cursor], " (vim-string (out "moved-latest")) ")")
                "qa!"
                ""]))
        (reset! process
                (.start (doto (ProcessBuilder. [vim-path "-N" "-u" "NONE" "-i" "NONE" "-es"
                                                "-S" (str script-file)])
                          (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null")))
                          (.redirectErrorStream true)
                          (.redirectOutput (io/file dir "vim.out")))))
        (is (t/eventually #(= ["ready"] (lines-of (out "ready"))) 30000)
            "vim holds both frames, cursor on the newest")
        (is (= 0 (:frame/index ((:carto-flow/previous! (addon/hooks flow))))))
        (is (t/eventually #(= ["0"] (lines-of (out "moved-back"))) 30000)
            "core's previous! moved the Vim cursor to frame 0")
        (is (= 1 (:frame/index ((:carto-flow/latest! (addon/hooks flow))))))
        (is (t/eventually #(= ["1"] (lines-of (out "moved-latest"))) 30000)
            "core's latest! moved it back to the newest frame")
        (is (.waitFor ^Process @process vim-timeout-seconds TimeUnit/SECONDS) "vim exited in time")
        (finally
          (when-let [^Process p @process]
            (when (.isAlive p)
              (println "vim output:" (slurp (io/file dir "vim.out")))
              (.destroyForcibly p)))
          (when vim (addon/shutdown! vim))
          (when flow (addon/shutdown! flow))
          (t/delete-tree! dir))))))

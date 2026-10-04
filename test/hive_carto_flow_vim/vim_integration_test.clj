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
            [hive-carto-flow-vim.test-support :as t]
            [hive-carto-flow-vim.paths :as paths]
            [clojure.data.json :as json]
            [clojure.java.shell :as sh])
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
    ;; The end state, not a message count: an extra replay must not end the wait early.
    "call s:wait('carto_flow#status().received >= 4 && len(carto_flow#frames()) >= 2 && carto_flow#connected()', 30.0)"
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
                                        {:carto-flow.vim/state-dir (str dir)}
                                        t/no-provisioned-install)])
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
        (do (is (t/eventually #(= 1 (clients)) 20000) "vim connected")
            (is (t/eventually #(= #{:carto-flow/timeline :carto-flow/seek :carto-flow/diff-walk}
                                  (:features (:details (addon/health vim))))
                              20000)
                "the shipped script's features, confirmed function by function by a real Vim"))
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

(defn- run-plugin-script
  "Source the shipped plugin in a headless Vim, run LINES, and return the list
   the script left in s:out."
  [dir name lines]
  (let [plugin-dir (-> (io/resource "vim/plugin/carto_flow.vim") io/file
                       .getParentFile .getParentFile str)
        out (io/file dir (str name ".out"))
        script (io/file dir (str name ".vim"))]
    (spit script
          (str/join "\n"
                    (concat [(str "execute 'set rtp^=' . fnameescape(" (vim-string plugin-dir) ")")]
                            lines
                            [(str "call writefile(s:out, " (vim-string (str out)) ")")
                             "qa!"
                             ""])))
    (let [p (.start (doto (ProcessBuilder. [vim-path "-N" "-u" "NONE" "-i" "NONE" "-es"
                                            "-S" (str script)])
                      (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null")))
                      (.redirectErrorStream true)
                      (.redirectOutput (io/file dir (str name ".vim.out")))))]
      (is (.waitFor p vim-timeout-seconds TimeUnit/SECONDS) "vim exited in time")
      (lines-of out))))

(deftest headless-vim-walks-frame-stops-and-renders-text-properties
  (if-not (and (vim-with-channels?) (str/includes? (:out (sh/sh vim-path "--version")) "+textprop"))
    (println "SKIP vim walk: needs +channel +textprop")
    (let [dir (t/temp-dir)
          first-file (io/file dir "first.clj")
          second-file (io/file dir "second.clj")
          stop (fn [frame path focus of added removed]
                 {"stop/frame" frame "stop/path" (str path) "stop/focus" focus
                  "stop/of" of "stop/added" added "stop/removed" removed
                  "stop/forms" ["example/f"]})]
      (try
        (spit first-file "one\ntwo\nthree\nfour\nfive\n")
        (spit second-file "alpha\nbeta\n")
        (let [stops [(stop 0 first-file 2 [1 2] [{"start" 2 "count" 1}]
                           [{"above" 2 "lines" ["old two"]}])
                     (stop 0 first-file 4 [2 2] [{"start" 4 "count" 1}] [])
                     (stop 1 second-file 2 [1 1] []
                           [{"above" 3 "lines" ["old tail"]}])]
              frame (fn [index xs] {"index" index "line" (str "frame " index)
                                    "detail" [(str "frame " index)] "stops" xs})
              results (run-plugin-script
                       dir "walk"
                       ["let g:carto_flow_follow_edits = 0"
                        "runtime plugin/carto_flow.vim"
                        "call carto_flow#hello({'frames': 2})"
                        (str "call carto_flow#ingest(" (json/write-str (frame 0 (subvec (vec stops) 0 2))) ")")
                        (str "call carto_flow#ingest(" (json/write-str (frame 1 [(nth stops 2)])) ")")
                        "CartoFlow"
                        "let s:timeline = win_getid()"
                        "let s:out = []"
                        "function! s:snapshot() abort"
                        "  let l:code = filter(range(1, winnr('$')), '!empty(bufname(winbufnr(v:val))) && bufname(winbufnr(v:val)) !~# \"^carto-flow://\"')[0]"
                        "  let l:buf = winbufnr(l:code)"
                        "  call add(s:out, json_encode({'frame': carto_flow#status().cursor, 'focus': win_getid() == s:timeline, 'path': bufname(l:buf), 'line': line('.', win_getid(l:code)), 'props': prop_list(2, {'bufnr': l:buf}) + prop_list(3, {'bufnr': l:buf}) + prop_list(4, {'bufnr': l:buf})}))"
                        "endfunction"
                        "normal n"
                        "call s:snapshot()"
                        "runtime autoload/carto_flow.vim"
                        "normal n"
                        "call s:snapshot()"
                        "normal n"
                        "call s:snapshot()"
                        "normal n"
                        "call s:snapshot()"
                        "call carto_flow#detail()"
                        "normal p"
                        "call s:snapshot()"
                        (str "call win_gotoid(bufwinid(bufnr('" first-file "')))")
                        "normal ]h"
                        "call s:snapshot()"
                        "CartoFlowWalkStop"
                        (str "call add(s:out, json_encode({'mode': carto_flow#stop_status().mode, 'props': prop_list(2, {'bufnr': bufnr('" second-file "')})}))")])
              snapshots (mapv #(json/read-str % :key-fn keyword) results)
              props (fn [i] (:props (nth snapshots i)))]
          (is (= [0 0 1 1] (mapv :frame (take 4 snapshots))) (pr-str results))
          (is (every? :focus (take 4 snapshots)) "focus stays in timeline")
          (is (= (mapv str [first-file first-file second-file second-file])
                 (mapv :path (take 4 snapshots))))
          (is (= [2 4 2 2] (mapv :line (take 4 snapshots))))
          (is (some #(= "cartoFlowAddLine" (:type %)) (props 0)))
          (is (some #(= "cartoFlowAddLine" (:type %)) (props 1)))
          (is (some #(and (= "cartoFlowDelText" (:type %))
                          (= "- old two" (:text %))
                          (= "above" (:text_align %))) (props 0)))
          (is (some #(and (= "cartoFlowDelText" (:type %))
                          (= "- old tail" (:text %))
                          (= "below" (:text_align %))) (props 2)))
          (is (= [0 1] (mapv :frame (subvec snapshots 4 6))) "detail p and code ]h walk back and forward")
          (is (every? :focus (subvec snapshots 4 6)) "both return to timeline")
          (is (= {:mode 0 :props []} (last snapshots))))
        (finally (t/delete-tree! dir))))))

(deftest headless-vim-opens-paged-multifile-code-view
  (if-not (and (vim-with-channels?) (str/includes? (:out (sh/sh vim-path "--version")) "+textprop"))
    (println "SKIP code view: needs +channel +textprop")
    (let [dir (t/temp-dir)
          paths (mapv #(io/file dir (str "file" % ".clj")) (range 4))
          stop (fn [frame file focus added removed]
                 {"stop/frame" frame "stop/path" (str file) "stop/focus" focus
                  "stop/of" [1 1] "stop/added" added "stop/removed" removed
                  "stop/forms" ["example/f"]})
          frame (fn [index stops] {"index" index "line" (str "frame " index)
                                    "detail" [(str "frame " index)] "stops" stops})]
      (try
        (doseq [file paths] (spit file "one\ntwo\nthree\nfour\nfive\n"))
        (let [first-stops [(stop 0 (paths 0) 2 [{"start" 2 "count" 1}]
                                 [{"above" 4 "lines" ["removed"]}])
                           (stop 0 (paths 0) 4 [{"start" 4 "count" 1}] [])
                           (stop 0 (paths 1) 3 [{"start" 3 "count" 1}] [])]
              four-stops (mapv (fn [file] (stop 1 file 2 [{"start" 2 "count" 1}] [])) paths)
              result (run-plugin-script
                      dir "code-view"
                      ["let g:carto_flow_follow_edits = 0"
                       "runtime plugin/carto_flow.vim"
                       "call carto_flow#hello({'frames': 3})"
                       (str "call carto_flow#ingest(" (json/write-str (frame 0 first-stops)) ")")
                       (str "call carto_flow#ingest(" (json/write-str (frame 1 four-stops)) ")")
                       (str "call carto_flow#ingest(" (json/write-str (frame 2 [(stop 2 (paths 2) 5 [] [])])) ")")
                       "CartoFlow"
                       "let s:timeline = win_getid()"
                       (str "let s:first = " (vim-string (str (paths 0))))
                       "let s:out = []"
                       "function! s:snapshot() abort"
                       "  let l:wins = filter(range(1, winnr('$')), '!empty(bufname(winbufnr(v:val))) && bufname(winbufnr(v:val)) !~# \"^carto-flow://\"')"
                       "  let l:rows = map(l:wins, '{\"path\": bufname(winbufnr(v:val)), \"line\": line(\".\", win_getid(v:val)), \"signs\": sign_getplaced(winbufnr(v:val), {\"group\": \"cartoflow\"})[0].signs}')"
                       "  call add(s:out, json_encode({'focus': win_getid() == s:timeline, 'windows': l:rows, 'props': prop_list(2, {'bufnr': bufnr(s:first)}) + prop_list(4, {'bufnr': bufnr(s:first)}), 'combine': prop_type_get('cartoFlowAddLine').combine, 'messages': execute('messages')}))"
                       "endfunction"
                       "call cursor(4, 1)"
                       "execute \"normal \\<CR>\""
                       "call s:snapshot()"
                       "call carto_flow#code_view(1)"
                       "call s:snapshot()"
                       "call feedkeys(\"\\<Tab>\", 'xt')"
                       "call s:snapshot()"
                       "call carto_flow#code_view(0)"
                       "call s:snapshot()"
                       "call carto_flow#code_view(1)"
                       "call cursor(6, 1)"
                       "execute \"normal \\<CR>\""
                       "call s:snapshot()"
                       "call carto_flow#code_view(0)"
                       "call s:snapshot()"])
              rows (mapv #(json/read-str % :key-fn keyword) result)]
          (is (= 6 (count rows)) (pr-str result))
          (is (every? :focus rows) "timeline keeps focus")
          (is (= (mapv str (take 2 paths)) (mapv :path (:windows (rows 0)))))
          (is (= [2 3] (mapv :line (:windows (rows 0)))))
          (is (= 3 (count (:windows (rows 1)))) "three files at once")
          (is (str/includes? (:messages (rows 1)) "+1 more files (Tab for next page)"))
          (is (= [(str (paths 3))] (mapv :path (:windows (rows 2)))) "Tab pages to fourth")
          (is (= 2 (count (:windows (rows 3)))) "next frame closes extra windows")
          (is (some #(= "cartoFlowAddLine" (:type %)) (:props (rows 0))))
          (is (= [(str (paths 2))] (mapv :path (:windows (rows 4)))) "a one-file frame closes extras")
          (is (= [5] (mapv :line (:windows (rows 4)))))
          (is (some #(and (= "cartoFlowDelText" (:type %)) (= "- removed" (:text %))) (:props (rows 0))))
          (is (= 1 (:combine (rows 0))))
          (is (some #(= "cartoFlowPlus" (:name %)) (-> rows first :windows first :signs)))
          (is (some #(= "cartoFlowMinus" (:name %)) (-> rows first :windows first :signs))))
        (finally (t/delete-tree! dir))))))

(deftest the-default-keys-map-the-named-actions-and-never-take-a-key-that-is-taken
  (if-not (vim-with-channels?)
    (println "SKIP vim integration: no" vim-path "with +channel +timers")
    (let [dir (t/temp-dir)]
      (try
        (is (= ["<Plug>(carto-flow-toggle)"
                "<Plug>(carto-flow-latest)"
                ":echo 'mine'<CR>"
                "<Plug>(carto-flow-connect)"
                "<Plug>(carto-flow-toggle)"
                "<Plug>(carto-flow-follow)"
                "hidden" "shown" "hidden"]
               (run-plugin-script
                dir "maps"
                ["let mapleader = ','"
                 ;; taken before the plugin loads: the plugin must leave it be,
                 ;; and must still give that action its other default key.
                 "nnoremap ,ce :echo 'mine'<CR>"
                 "runtime plugin/carto_flow.vim"
                 "let s:out = []"
                 "for s:key in [',cf', ',cl', ',ce', ',cd', '<F9>', '<S-F9>']"
                 "  call add(s:out, maparg(s:key, 'n'))"
                 "endfor"
                 "function! s:visible() abort"
                 "  return bufwinid(bufnr('carto-flow://timeline')) == -1 ? 'hidden' : 'shown'"
                 "endfunction"
                 "call add(s:out, s:visible())"
                 "call carto_flow#toggle()"
                 "call add(s:out, s:visible())"
                 "call carto_flow#toggle()"
                 "call add(s:out, s:visible())"]))
            "every free key maps to its named action, a taken one is untouched, and toggle shows then hides")
        (is (= ["free" "free" "free" "free" "free" "free" "plug"]
               (run-plugin-script
                dir "no-maps"
                ["let mapleader = ','"
                 "let g:carto_flow_no_default_maps = 1"
                 "runtime plugin/carto_flow.vim"
                 "let s:out = []"
                 "for s:key in [',cf', ',cl', ',ce', ',cd', '<F9>', '<S-F9>']"
                 "  call add(s:out, empty(maparg(s:key, 'n')) ? 'free' : 'mapped')"
                 "endfor"
                 "call add(s:out, empty(maparg('<Plug>(carto-flow-toggle)', 'n')) ? 'no-plug' : 'plug')"]))
            "the opt-out leaves every key free while the named actions stay bindable")
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
                                        {:carto-flow.vim/state-dir (str dir)}
                                        t/no-provisioned-install)])
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

(deftest a-vim-running-a-stale-plugin-is-hot-reloaded-on-connect
  (if-not (vim-with-channels?)
    (println "SKIP vim integration: no" vim-path "with +channel +timers")
    (let [dir (t/temp-dir)
          host (mount/atom-mount-host)
          report (mount/mount!
                  (mount/solve [t/carto-spec
                                (t/classpath-manifest "hive-carto-flow.edn")
                                (update (t/classpath-manifest "hive-carto-flow-vim.edn")
                                        :addon/config merge
                                        {:carto-flow.vim/state-dir (str dir)}
                                        t/no-provisioned-install)])
                  host
                  {:license-gate (t/permit-only #{"hive.carto-flow" "hive.carto-flow.vim"})})
          vim (mount-port/registered host "hive.carto-flow.vim")
          flow (mount-port/registered host "hive.carto-flow")
          out (fn [name] (str (io/file dir name)))
          ;; An older install: the same plugin without :CartoFlowLayout, from
          ;; before carto_flow#seek and carto_flow#features(), advertising the
          ;; timeline through the g:carto_flow_features literal plugin/ set.
          stale (io/file dir "stale")
          _ (doseq [rel paths/plugin-files]
              (let [text (slurp (io/resource (str paths/plugin-resource-root rel)))]
                (io/make-parents (io/file stale rel))
                (spit (io/file stale rel)
                      (case rel
                        "plugin/carto_flow.vim"
                        (-> text
                            (str/replace #"(?m)^command! .*CartoFlowLayout\n.*\n" "")
                            (str/replace #"(?m)^command! -nargs=\? CartoFlow "
                                         "let g:carto_flow_features = get(g:, 'carto_flow_features', ['carto-flow/timeline'])\n$0"))
                        "autoload/carto_flow.vim"
                        (-> text
                            (str/replace #"(?ms)^function! carto_flow#seek\(.*?^endfunction\n" "")
                            (str/replace #"(?ms)^function! carto_flow#features\(.*?^endfunction\n" ""))
                        text))))
          script-file (io/file dir "stale.vim")
          process (atom nil)]
      (try
        (is (:ok? report) (pr-str (:mounted report)))
        (t/mutate! :carto.mutation/succeeded ["src/a.clj"])
        (spit script-file
              (str/join
               "\n"
               [(str "let g:carto_flow_port_file = " (vim-string (out "vim.port")))
                "let g:carto_flow_reconnect_ms = 50"
                "let g:carto_flow_follow_edits = 0"
                (str "execute 'set rtp^=' . fnameescape(" (vim-string (str stale)) ")")
                "runtime plugin/carto_flow.vim"
                "let s:out = [exists(':CartoFlowLayout')]"
                "function! s:wait(cond, seconds) abort"
                "  let l:start = reltime()"
                "  while !eval(a:cond) && reltimefloat(reltime(l:start)) < a:seconds"
                "    sleep 20m"
                "  endwhile"
                "endfunction"
                "CartoFlow"
                "call s:wait('exists(\":CartoFlowLayout\") == 2 && len(carto_flow#frames()) >= 1', 30.0)"
                "call extend(s:out, [exists(':CartoFlowLayout'), get(g:, 'carto_flow_runtime', ''),"
                "      \\ len(carto_flow#frames()), carto_flow#connected()])"
                (str "call writefile(s:out, " (vim-string (out "stale.out")) ")")
                "qa!"
                ""]))
        (reset! process
                (.start (doto (ProcessBuilder. [vim-path "-N" "-u" "NONE" "-i" "NONE" "-es"
                                                "-S" (str script-file)])
                          (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null")))
                          (.redirectErrorStream true)
                          (.redirectOutput (io/file dir "vim.out")))))
        (is (.waitFor ^Process @process vim-timeout-seconds TimeUnit/SECONDS) "vim exited in time")
        (is (= ["0" "2" (paths/runtime-hash) "1" "1"] (lines-of (out "stale.out")))
            (str "the stale plugin lacked :CartoFlowLayout; on connect the server re-sourced"
                 " the current runtime into the running Vim, which kept its channel and"
                 " received the replay"))
        (is (= {:hash (paths/runtime-hash) :reloaded? true}
               (:runtime (:details (addon/health vim))))
            "health reports the reload")
        (is (= 1 (:connections (:details (addon/health vim))))
            "the reloaded script took the old one's channel over: no second connection")
        (is (= #{:carto-flow/timeline :carto-flow/seek :carto-flow/diff-walk}
               (:features (:details (addon/health vim))))
            (str "the sync ran before the probe: the stale script defined no carto_flow#seek,"
                 " the reloaded one does, so the handshake granted :carto-flow/seek"))
        (finally
          (when-let [^Process p @process]
            (when (.isAlive p)
              (println "vim output:" (slurp (io/file dir "vim.out")))
              (.destroyForcibly p)))
          (when vim (addon/shutdown! vim))
          (when flow (addon/shutdown! flow))
          (t/delete-tree! dir))))))

(deftest the-panel-docks-by-layout-and-v-cycles-the-layouts
  (if-not (vim-with-channels?)
    (println "SKIP vim integration: no" vim-path "with +channel +timers")
    (let [dir (t/temp-dir)]
      (try
        (is (= ["left-1/3 timeline:27x22@1,1 frame:27x22@1,1 code:52x22@1,29"
                "left-1/3 timeline:27x9@1,1 frame:27x12@11,1 code:52x22@1,29"
                "left-1/2 timeline:40x9@1,1 frame:40x12@11,1 code:39x22@1,42"
                "bottom-1/3 timeline:32x8@15,1 frame:47x8@15,34 code:80x13@1,1"
                "bottom-1/2 timeline:32x12@11,1 frame:47x12@11,34 code:80x9@1,1"
                "bottom-1/3 timeline:32x8@15,1 frame:47x8@15,34 code:80x13@1,1"
                "right-1/4 timeline:20x9@1,61 frame:20x12@11,61 code:59x22@1,1"
                "right-1/4" "left-1/3,left-1/2"
                "hidden"
                "left-1/2 timeline:40x22@1,1 frame:40x22@1,1 code:39x22@1,42"
                "classic"]
               (run-plugin-script
                dir "layout"
                ;; -es keeps an 80x24 screen: geometry below is for it.
                ["runtime plugin/carto_flow.vim"
                 "let s:out = []"
                 "function! s:geo(buf) abort"
                 "  let l:w = bufwinid(a:buf)"
                 "  let l:w = l:w == -1 ? bufwinid(bufnr('carto-flow://timeline')) : l:w"
                 "  let l:p = win_screenpos(l:w)"
                 "  return printf('%dx%d@%d,%d', winwidth(l:w), winheight(l:w), l:p[0], l:p[1])"
                 "endfunction"
                 "function! s:dump() abort"
                 "  let l:code = filter(range(1, winnr('$')), 'bufname(winbufnr(v:val)) !~# \"^carto-flow://\"')[0]"
                 "  call add(s:out, carto_flow#current_layout()"
                 "        \\ . ' timeline:' . s:geo(bufnr('carto-flow://timeline'))"
                 "        \\ . ' frame:' . s:geo(bufnr('carto-flow://frame'))"
                 "        \\ . ' code:' . s:geo(winbufnr(l:code)))"
                 "endfunction"
                 "call carto_flow#hello({'frames': 0})"
                 "call carto_flow#ingest({'index': 0, 'line': 'f0', 'detail': ['+a']})"
                 "CartoFlow 1"
                 "call s:dump()"
                 "call carto_flow#detail()"
                 "call s:dump()"
                 "normal v"
                 "call s:dump()"
                 "normal v"
                 "call s:dump()"
                 "normal v"
                 "call s:dump()"
                 "normal V"
                 "call s:dump()"
                 "CartoFlowLayout right-1/4"
                 "call s:dump()"
                 "silent! CartoFlowLayout sideways"
                 "call add(s:out, carto_flow#current_layout())"
                 "call add(s:out, join(carto_flow#layout_complete('left', '', 0), ','))"
                 "call carto_flow#toggle()"
                 "call add(s:out, bufwinid(bufnr('carto-flow://frame')) == -1 ? 'hidden' : 'shown')"
                 "CartoFlowLayout left-1/2"
                 "call carto_flow#toggle()"
                 "call s:dump()"
                 "unlet g:carto_flow_layout"
                 "let g:carto_flow_position = 'botright'"
                 "call add(s:out, carto_flow#current_layout())"]))
            (str "the panel takes its share of the screen on its edge, timeline and detail"
                 " share it, v and V cycle, a named layout docks at once, an unknown one is"
                 " refused, a hidden panel opens on the layout chosen meanwhile, and"
                 " g:carto_flow_position alone keeps the classic placement"))
        (finally
          (t/delete-tree! dir))))))

(deftest editing-the-plugin-reaches-the-connected-vim-without-a-reconnect
  (if-not (vim-with-channels?)
    (println "SKIP vim integration: no" vim-path "with +channel +timers")
    (let [dir (t/temp-dir)
          ;; A writable copy of resources/vim stands in for a source checkout:
          ;; the watched root is injected through config, so the test never
          ;; edits the repository.
          root (io/file dir "runtime")
          _ (doseq [rel paths/plugin-files]
              (io/make-parents (io/file root rel))
              (spit (io/file root rel) (slurp (io/resource (str paths/plugin-resource-root rel)))))
          host (mount/atom-mount-host)
          report (mount/mount!
                  (mount/solve [t/carto-spec
                                (t/classpath-manifest "hive-carto-flow.edn")
                                (update (t/classpath-manifest "hive-carto-flow-vim.edn")
                                        :addon/config merge
                                        {:carto-flow.vim/state-dir (str dir)
                                         :carto-flow.vim/runtime-root (str root)
                                         :carto-flow.vim/watch-interval-ms 50}
                                        t/no-provisioned-install)])
                  host
                  {:license-gate (t/permit-only #{"hive.carto-flow" "hive.carto-flow.vim"})})
          vim (mount-port/registered host "hive.carto-flow.vim")
          flow (mount-port/registered host "hive.carto-flow")
          out (fn [name] (str (io/file dir name)))
          script-file (io/file dir "watch.vim")
          process (atom nil)
          before (paths/runtime-hash root)]
      (try
        (is (:ok? report) (pr-str (:mounted report)))
        (is (:watching? (:runtime-watch (:details (addon/health vim))))
            "a runtime that can change is watched")
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
                (str "call s:wait('len(carto_flow#frames()) >= 1 && get(g:, \"carto_flow_runtime\", \"\") ==# "
                     (str/replace (vim-string before) "'" "''") "', 30.0)")
                (str "call writefile([get(g:, 'carto_flow_runtime', '')], " (vim-string (out "ready")) ")")
                "call s:wait('exists(\"g:carto_flow_watch_edit\")', 30.0)"
                (str "call writefile([get(g:, 'carto_flow_watch_edit', ''), get(g:, 'carto_flow_runtime', ''),"
                     " len(carto_flow#frames()), carto_flow#connected()], " (vim-string (out "edited")) ")")
                "qa!"
                ""]))
        (reset! process
                (.start (doto (ProcessBuilder. [vim-path "-N" "-u" "NONE" "-i" "NONE" "-es"
                                                "-S" (str script-file)])
                          (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null")))
                          (.redirectErrorStream true)
                          (.redirectOutput (io/file dir "vim.out")))))
        (is (t/eventually #(= [before] (lines-of (out "ready"))) 30000)
            "the connected Vim runs the watched runtime")
        (is (= 0 (:changes (:runtime-watch (:details (addon/health vim)))))
            "unchanged content never fires the watch")
        (spit (io/file root "plugin/carto_flow.vim")
              "\nlet g:carto_flow_watch_edit = 'edited'\n" :append true)
        (let [after (paths/runtime-hash root)]
          (is (not= before after))
          (is (t/eventually #(lines-of (out "edited")) 30000) "the edit reached the Vim")
          (is (= ["edited" after "1" "1"] (lines-of (out "edited")))
              "the running Vim re-sourced the edited plugin, kept its frames and channel")
          (is (.waitFor ^Process @process vim-timeout-seconds TimeUnit/SECONDS) "vim exited in time")
          (let [details (:details (addon/health vim))]
            (is (= {:hash after :reloaded? true :trigger :watch} (:runtime details))
                "health reports the watch-driven sync")
            (is (= 1 (:changes (:runtime-watch details))))
            (is (= 1 (:connections details)) "no reconnect: the same connection carried the sync")))
        (finally
          (when-let [^Process p @process]
            (when (.isAlive p)
              (println "vim output:" (slurp (io/file dir "vim.out")))
              (.destroyForcibly p)))
          (when vim (addon/shutdown! vim))
          (when flow (addon/shutdown! flow))
          (t/delete-tree! dir))))))

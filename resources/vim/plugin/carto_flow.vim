" carto_flow.vim -- Carto Flow timeline presenter for hive.carto-flow.vim
"
" Commands:
"   :CartoFlow [port|host:port]         open the timeline buffer and connect
"   :CartoFlowConnect [port|host:port]  connect without opening the buffer
"   :CartoFlowDisconnect                 close the channel, stop reconnecting
"   :CartoFlowClear                      forget the frames shown in this Vim
"   :CartoFlowCode                       open the code of the frame under the cursor
"   :CartoFlowFollow [on|off]            follow live edits into the code, or flip it
"
" Keys, in the timeline and in the carto-flow://frame detail:
"   n ]f  next frame     p [f  previous frame     G  latest, then follow
"   o     open the frame's code at its first changed line
"   <CR>  (timeline) open the detail     q  close the window
"
" g:carto_flow_autoconnect = 1 connects on VimEnter without :CartoFlow. When
" hive.carto-flow.vim is mounted with a runtime provisioner, a loader it
" installs next to this plugin does that for you.

if exists('g:loaded_carto_flow')
  finish
endif
let g:loaded_carto_flow = 1

if !has('channel') || !has('timers')
  echohl WarningMsg
  echomsg 'carto-flow: this Vim lacks +channel or +timers'
  echohl None
  finish
endif

" What this Vim advertises to hive over the hive-vessel handshake. The server
" reads it (eval of g:carto_flow_features) and only then lowers a carto-flow
" frame to carto_flow#ingest; a Vim without this plugin gets the generic
" hive-vessel panel instead.
let g:carto_flow_features = get(g:, 'carto_flow_features', ['carto-flow/timeline'])

command! -nargs=? CartoFlow call carto_flow#open(<f-args>)
command! -nargs=? CartoFlowConnect call carto_flow#connect(<f-args>)
command! -nargs=0 CartoFlowDisconnect call carto_flow#disconnect()
command! -nargs=0 CartoFlowClear call carto_flow#clear()
command! -nargs=0 CartoFlowCode call carto_flow#open_code()
command! -nargs=? -complete=customlist,carto_flow#follow_complete CartoFlowFollow
      \ call carto_flow#follow_edits(<f-args>)

if get(g:, 'carto_flow_autoconnect', 0)
  if v:vim_did_enter
    call carto_flow#connect()
  else
    augroup carto_flow_autoconnect
      autocmd!
      autocmd VimEnter * ++once call carto_flow#connect()
    augroup END
  endif
endif

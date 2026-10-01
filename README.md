# Carto Flow for Vim

The `hive.carto-flow.vim` IAddon, MIT. It is the Vim vessel extension of
`hive.carto-flow`: it registers the presenter `:vim` on the core timeline and
shows every Carto operation frame in a Vim buffer.

The addon runs hive-vessel's `:vim-channel` executor on `127.0.0.1`, which
speaks Vim's JSON channel protocol (`:help channel-commands`). Each frame reaches
Vim as `["call", "carto_flow#ingest", [message]]`, and the message already
carries the rendered timeline line and detail lines, so Vim only lays them out.
A Vim that connects late first receives the whole timeline. The executor holds
one Vim connection at a time: a newly connected Vim replaces the previous one.

The listening port is written to `$XDG_STATE_HOME/hive/carto-flow/vim.port`
(`~/.local/state/...` when `XDG_STATE_HOME` is unset) and removed on shutdown.
Set `:carto-flow.vim/port` in the addon config for a fixed port; the default 0
picks a free one.

## Vim setup

Needs Vim 8.2 or later built with `+channel`, `+timers` and `+packages`.

### Automatic, at injection

The manifest declares an `:addon/runtime` (hive-addon's client-runtime seam).
When the host mounts the addon with a runtime provisioner
(`hive-addon.runtime.boundary/provisioner` passed to `mount!` as `:provision`),
injection installs the plugin into Vim's package path,
`~/.vim/pack/hive/start/hive-carto-flow`, with a generated loader. Every Vim
started afterwards loads it and, once it has entered, connects to this addon's
port file and opens the timeline on the first live change. A Vim that is
already running is activated through the provisioner's `:eval-fn` when the host
supplies one. Nothing has to be typed. Tearing the addon down with the matching
`:deprovision` removes the install.

### Manual

The addon also extracts its plugin to a stable directory, by default
`~/.local/state/hive/carto-flow/vim-plugin` (the `:carto-flow.vim/plugin-dir`
hook returns the exact path). Add it to your runtime path:

```vim
set rtp+=~/.local/state/hive/carto-flow/vim-plugin
syntax on
```

Then run `:CartoFlow`, or set `g:carto_flow_autoconnect = 1` to connect on
VimEnter. It connects using the port file and reconnects on its own when the
server restarts.

### Hot reload

A running Vim never needs a restart to pick up a newer plugin. On every
connection the addon extracts its runtime again and compares a hash of the
carto_flow plugin with `g:carto_flow_runtime` in the Vim. When they differ it
re-sources the plugin, autoload first, from the extracted directory, through
Vim's `execute()` over the channel. Old plugins need no new code for this. The
timeline keeps its frames, cursor and connection, even when the old copy was
loaded from another directory. So a hot reload of the addon (a new version
mounted in a live hive) reaches every connected Vim within one reconnect
interval. The addon's health reports the last sync under `:runtime`
(`{:hash ... :reloaded? true}`). `g:carto_flow_no_sync = 1` opts a Vim out.

Edits to the plugin itself reach a connected Vim with no remount and no
reconnect. When the plugin's resources are real files (a source checkout or a
`:local/root` dependency), the addon polls their hash on a daemon thread every
`:carto-flow.vim/watch-interval-ms` (default 500). A new hash is acted on once
it has held for one poll, so a half-written save is never pushed. The addon
then extracts the runtime again and runs the same sync over the live channel.
It does not restart the channel or replay the timeline, and unchanged content
never triggers it. Health shows the watcher under `:runtime-watch`
(`:watching?`, `:hash`, `:changes`, `:last-error`) and its last push under
`:runtime` with `:trigger :watch`. A jar-backed plugin cannot change, so no
watcher is started for it. To watch a directory laid out like `resources/vim`
instead of the classpath, set `:carto-flow.vim/runtime-root`. To turn the
watcher off, set `:carto-flow.vim/watch-runtime? false`.

## Using the timeline

Live frames follow the edit: the changed file opens at the first changed line
of the frame's diff, in a window that is not the timeline, and focus stays
where it was (`g:carto_flow_follow_edits`, default on; `:CartoFlowFollow`
flips it, `:CartoFlowFollow on|off` sets it). Relative frame paths
resolve against `g:carto_flow_roots`, then the current directory.

These keys work in the timeline and in the `carto-flow://frame` detail:

| Key | Action |
| --- | --- |
| `]f` / `n` | next frame (an open detail re-renders in place) |
| `[f` / `p` | previous frame |
| `G` | latest frame, and follow new ones |
| `o` | open the frame's code at its first changed line |
| `<CR>` | (timeline) open the frame's detail and diff in a split |
| `q` | close the window |
| `v` / `V` | next / previous layout (see below) |
| `=` | re-fit the panel to the screen |

Core's cursor drives this one: when anything moves the core timeline cursor
(`:carto-flow/next!`, `:carto-flow/previous!`, `:carto-flow/latest!`, from
Emacs, a tool call, or another vessel), the connected Vim's cursor moves to the
same frame and an open detail re-renders. Moving with `n`/`p` inside Vim stays
local to that Vim.

## Layout

The timeline and the frame detail share one panel, docked on an edge of the
screen and taking a fixed share of it; the code keeps the rest. A layout names
both: `left-1/3` is a full-height column a third of the screen wide, with the
timeline stacked over the detail; `bottom-1/2` is a full-width row half the
screen tall, with the timeline beside the detail. Any of `left`, `right`, `top`
and `bottom` with any fraction `N/D` works.

| Setting | Default |
| --- | --- |
| `g:carto_flow_layout` | `'left-1/3'` (`'classic'` when only `g:carto_flow_position` is set) |
| `g:carto_flow_layouts` | `['left-1/3', 'left-1/2', 'bottom-1/3', 'bottom-1/2']`, the ones `v`/`V` cycle |
| `g:carto_flow_timeline_share` | `0.4`, the timeline's part of the panel beside the detail |

`v` and `V` in the panel, `<leader>cv` anywhere, and `:CartoFlowLayout` with no
argument step through `g:carto_flow_layouts`; `:CartoFlowLayout bottom-1/3`
(tab-completes) docks on a named one. A shown panel re-docks at once and keeps
focus where it was; a hidden one opens on the new layout next time. The panel
re-fits on a terminal resize. `classic` is the old placement: the timeline at
`g:carto_flow_position`, the detail split below it.


## Global keys

The timeline keys above only work inside it, so the plugin also maps five
actions globally. `<leader>` is your `mapleader`.

| Key | Action |
| --- | --- |
| `<leader>cf`, `<F9>` | show or hide the timeline (`:CartoFlowToggle`) |
| `<leader>cl` | open it on the newest frame and follow from there |
| `<leader>ce`, `<S-F9>` | follow live edits into the code, or stop |
| `<leader>cd` | disconnect, or connect again |
| `<leader>cv` | next layout (`:CartoFlowLayout`) |

Hiding the panel closes the timeline and the detail and keeps their buffers
and frames, so the next toggle shows the same view rather than an empty one.

Each key is installed only when it is free and nothing you wrote already
reaches that action, so a vimrc binding wins:

```vim
nmap <F5> <Plug>(carto-flow-toggle)   " the default <leader>cf and <F9> stand down
```

The five named mappings are `<Plug>(carto-flow-toggle)`,
`<Plug>(carto-flow-latest)`, `<Plug>(carto-flow-follow)`,
`<Plug>(carto-flow-connect)` and `<Plug>(carto-flow-layout)`. `g:carto_flow_no_default_maps = 1` refuses the
default set entirely and leaves them for you to bind.

Commands: `:CartoFlow [port]`, `:CartoFlowConnect [port]`,
`:CartoFlowDisconnect`, `:CartoFlowCode`, `:CartoFlowFollow [on|off]`,
`:CartoFlowToggle`, `:CartoFlowLayout [name]`, and `:CartoFlowClear`, which clears only this Vim's view.
`g:carto_flow_auto_open = 1` opens the timeline on the first live frame
without moving focus.

Neovim is not supported, because it has no Vim JSON channels. A separate
`hive-carto-flow-nvim` addon using msgpack-rpc is the intended route.

## Hooks

- `:carto-flow.vim/port` returns the listening port.
- `:carto-flow.vim/port-file` returns the port file path Vim reads.
- `:carto-flow.vim/plugin-dir` extracts the Vim plugin and returns its directory.
  Running it again is safe.
- `:carto-flow.vim/vessel` returns the hive-vessel target of the connected Vim.

## Development

```bash
clojure -Sdeps "$(cat local.deps.edn)" -M:test
```

`local.deps.edn` points `hive-carto-flow`, `hive-vessel`, `hive-addon`,
`hive-events` and `hive-dsl` at sibling checkouts; `clojure -M:test` runs
against the published jars instead, which is what CI does. The integration
test drives a real headless `/usr/bin/vim`, and the provision test drives a real Vim in tmux
started after injection; both are skipped when Vim or its features are missing.

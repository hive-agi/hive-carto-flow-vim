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

Core's cursor drives this one: when anything moves the core timeline cursor
(`:carto-flow/next!`, `:carto-flow/previous!`, `:carto-flow/latest!`, from
Emacs, a tool call, or another vessel), the connected Vim's cursor moves to the
same frame and an open detail re-renders. Moving with `n`/`p` inside Vim stays
local to that Vim.

Commands: `:CartoFlow [port]`, `:CartoFlowConnect [port]`,
`:CartoFlowDisconnect`, `:CartoFlowCode`, `:CartoFlowFollow [on|off]`, and
`:CartoFlowClear`, which clears
only this Vim's view. `g:carto_flow_auto_open = 1` opens the timeline on the
first live frame without moving focus.

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

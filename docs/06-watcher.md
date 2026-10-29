# 06 — Watcher

Parent: [SPEC.md](SPEC.md). Contract: [02-contracts.md](02-contracts.md).

## Watch paths

Always:

- `commonDir/refs/**`
- `commonDir/packed-refs`
- `gitDir/HEAD`, `gitDir/index`
- `gitDir/MERGE_HEAD`, `REBASE_HEAD`, `CHERRY_PICK_HEAD`, `REVERT_HEAD`
- `gitDir/rebase-merge/`, `gitDir/rebase-apply/`

Never watch `objects/`.

Work tree (`watchWorkTree = true`): recursive, excluding `.git`, `node_modules`, `build`, `target`,
`.gradle`, `.idea`, `dist`, `out`, `__pycache__`, `.venv`. Cap at 20,000 registered directories; if
exceeded, disable this part, show “partial watching”, and rely on focus refresh.

## Classification (pure, table-tested)

| Event path | Result |
|---|---|
| `*.lock` | ignore |
| refs, `packed-refs`, `HEAD` | `REFS_CHANGED` (also implies status) |
| `index`, operation files, `rebase-*` | `STATUS_CHANGED` |
| other file inside `gitDir` | ignore |
| work-tree path outside `.git` | `STATUS_CHANGED` |

## Debounce and loops

- Trailing 300 ms, maximum wait 2 s; `REFS_CHANGED` absorbs `STATUS_CHANGED`.
- Read commands use `GIT_OPTIONAL_LOCKS=0`, so `git status` does not rewrite the index.
- After a write, refresh explicitly and discard watcher events for 500 ms.

## Refresh paths

- Window regains focus → refresh status; refs too if changed.
- Manual refresh button.
- Watcher fails to start → poll every 3 s while active and focused; show a discreet warning.

## Session reaction

- `REFS_CHANGED` → `refs()` + `head()`. If unchanged (`data class equals`), do not reload the
  graph; otherwise refresh it. Always refresh status.
- `STATUS_CHANGED` → `status()` only.

The watcher is a latency optimization. With it disabled, every user action must still leave the UI
correct.

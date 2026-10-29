# 11 — Edge Cases

Parent: [SPEC.md](SPEC.md). Use this as a test checklist.

| Case | Expected behavior |
|---|---|
| Empty repository (unborn HEAD) | Empty graph; working tree shown; unstage/discard staged use `git rm --cached`; commit works with staged files. |
| Detached HEAD | Valid; no branch name; HEAD chip still shown. |
| Annotated tag | Peel to commit. |
| Tag to tree/blob | Exclude from refs. |
| `origin/HEAD` symref | Exclude. |
| Worktree/submodule `.git` file | Resolve `gitDir`/`commonDir` via `git rev-parse`. |
| Path with spaces/Unicode/newline | NUL parsing, UTF-8, literal pathspecs. |
| Rename/copy/typechange | Parse the extra NUL path; pass both paths to diff/stage. |
| Binary file | Show “Binary file”; no text diff. |
| Diff > 2 MB | Truncate; offer “load full”. |
| Line > 2,000 chars | Truncate on screen only. |
| Octopus merge | All secondary parents get merge edges; diff against first parent. |
| Shallow repo | Missing parents leave dangling lanes; not an error. |
| SHA-256 repo | Empty-tree hash computed per repo. |
| Merge/rebase/cherry-pick/revert | Banner + read-only conflicts; no stage/discard. |
| Duplicate tab | Activate the existing tab. |
| Close tab during a write | `DRAINING`; notify result; then close. |
| Watcher start failure | Poll every 3 s while active and focused. |
| Huge untracked tree | Respect `.gitignore`; `-uall` may be large; consider a future cap. |
| Git missing or < 2.30 | Clear startup error; do not crash. |
| No upstream / divergent pull / push rejected | Show Git stderr; no prompts. |
| Hook failure | Show hook output; app remains usable. |
| Credentials required | Fail fast; show the error. |
| Empty message or empty index | Commit disabled; backend also guards. |
| Concurrent writes | Serialize with the session `Mutex`; show busy. |
| App exits during a write | Process may die; no guarantees beyond Git's own. |
| File deleted between status and diff | Show a clear error; refresh status. |
| Repository moved/deleted | Mark tab unavailable; do not crash. |
| Permission denied | Show stderr; keep the session alive. |
| Invalid query/glob | Keep the last valid query; show the error. |
| No visible refs | Empty graph + “No visible refs”. |
| Pathspec starting with `-` | Pass after `--`; literal pathspecs. |
| CRLF/Unicode commit message | Preserve; render replacement chars only if needed. |
| Discard staged tracked | Index returns to HEAD; disk unchanged; change becomes unstaged. |
| Discard staged new file | Index entry removed; file remains on disk as untracked. |
| Discard staged rename | Index restores the old path and removes the new path; disk unchanged; status shows an unstaged deletion plus an untracked file. |
| Discard unstaged tracked | Work tree restored from index; index unchanged. |
| Discard untracked | File deleted after confirmation. |
| File staged and unstaged | Staged discard keeps the worktree; unstaged discard keeps the index. |
| Staged deletion | Index entry restored; file remains missing on disk, so it becomes an unstaged deletion. |
| Untracked directory | `-uall` lists files; deleting them may leave empty directories. |
| Symlink | Treat as a file; restore/delete according to status; never follow it for binary/size checks. |

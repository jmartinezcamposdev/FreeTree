# 03 — Git

Parent: [SPEC.md](SPEC.md). Contracts: [02-contracts.md](02-contracts.md).

## Process rules

- `ProcessBuilder` with an argument list; no shell, no direct `.git` writes.
- Working directory: repository work-tree root.
- Prefix: `git --no-pager -c core.quotepath=off`.
- Always: `GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never`, `GIT_LITERAL_PATHSPECS=1`.
- Read commands only: `GIT_OPTIONAL_LOCKS=0` (avoids index rewrites and watcher loops).
- `pull` only: `GIT_MERGE_AUTOEDIT=no`, `GIT_EDITOR=true`.
- Close `stdin` unless data is supplied (`commit -F -`, `log --stdin`).
- Decode UTF-8; use `-z` for paths.
- Capture `stdout`/`stderr`; preserve Git's original `stderr` on failure.
- Timeouts: reads 60 s, local writes 120 s, push/pull 5 min. On timeout kill the process and return
  an error.
- Cancellation: kill the process and descendants, close streams. A blocking read does not respond
  to coroutine cancellation by itself.
- Git discovery: `PATH`, then common locations, then `FREETREE_GIT`. Require Git ≥ 2.30 and check
  `git --version` at startup.
- Stream `log`; cap diffs at 2 MB.

## Command matrix

| Function | Command | Notes |
|---|---|---|
| Refs | `git for-each-ref --format=%(objectname)%00%(objecttype)%00%(*objectname)%00%(*objecttype)%00%(refname)%00%(HEAD)%00%(upstream:short)%00%(symref) refs/heads refs/remotes refs/tags` | NUL-separated fields, one record per line. Peel annotated tags. Drop symrefs (`origin/HEAD`) and refs not resolving to commits. |
| HEAD | `git rev-parse --verify -q HEAD` and `git symbolic-ref -q --short HEAD` | Unborn and detached are valid. |
| Log | `git log --stdin -z --topo-order --format=%H%x1f%P%x1f%an%x1f%at%x1f%s` | Revs via stdin, one per line. Split on `0x1F` with a 5-field limit. Skip malformed records. |
| Status | `git status --porcelain=v2 -z --branch --untracked-files=all` | Parse branch headers; entries `1`, `2` (rename old path after extra NUL), `u`, `?`; ignore `!`. |
| Base | first parent, or the empty tree | Empty-tree hash once per repo: `git hash-object -t tree --stdin` with empty stdin. |
| Changed files | `git diff-tree -r -z --name-status -M <base> <hash>` | Renames: `R100\0old\0new\0`. Merges compare to first parent. |
| Commit diff | `git diff --no-color --no-ext-diff -M <base> <hash> -- <path> [<oldPath>]` | Pass both paths for renames. |
| Work-tree diff | `git diff --no-color --no-ext-diff -- <path>`; staged: add `--cached` | Untracked: read file as all-added, with size/binary limits. |
| Commit detail | `git log -1 -z --format=%H%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%cn%x1f%ct%x1f%B <hash>` | `%B` last; split with limit 8. |
| Stage | `git add -- <path>` | Stages deletions too; both paths for renames. |
| Unstage | `git restore --staged -- <path>`; unborn HEAD: `git rm --cached -- <path>` | Index-only. |
| Discard staged | tracked: `git restore --staged --source=HEAD -- <path>`; staged new file or unborn HEAD: `git rm --cached -- <path>` | Index only; disk untouched. For a rename, restore the old path and remove the new path in the index. |
| Discard unstaged | tracked: `git restore --worktree -- <path>`; untracked: delete the file | Work-tree only; index untouched. Changes disk. Confirmation required. |
| Commit | `git commit -F -` with message on stdin | Disable on empty message or empty index. Show hook output on failure. |
| Push | `git push` | Respect upstream config; show Git's error verbatim. |
| Pull | `git pull --no-edit` | Respect user config; show divergence/conflict errors verbatim. |
| Git dirs | `git rev-parse --show-toplevel --absolute-git-dir --git-common-dir` | Resolve a relative common dir. `.git` may be a file. |

## Discard semantics

`discardStaged` removes the staged change from the index and leaves the file on disk untouched. A
staged new file becomes untracked. For each path: restore the index entry from HEAD if it exists;
otherwise remove it from the index with `git rm --cached`. For a rename, pass old and new paths.

`discardUnstaged` changes the file on disk: tracked files are restored from the index; untracked
files are deleted. It never changes the index, so staged changes remain staged.

- A file in both areas can use either action; staged keeps the worktree, unstaged keeps the index.
- Confirmation is required for unstaged actions; staged is index-only and reversible by re-staging.
- Conflicted (`UNMERGED`) files cannot be discarded.
- After either action, refresh status and refs explicitly.
- Treat both methods as writes: serialize with the session `Mutex`, show `BusyOp.DISCARD`, and
  surface Git errors.

## Diff limits

- Output over 2 MB: kill the process, set `truncated = true`, offer “load full” (unlimited).
- Display lines over 2,000 characters: truncate on screen only.
- Binary files: “Binary file”, no text diff.
- Untracked files use the same size/binary rules.

## Conflicts and in-progress operations

Do not resolve them. Detect `UNMERGED` entries and the presence of `MERGE_HEAD`, `REBASE_HEAD`,
`CHERRY_PICK_HEAD`, `REVERT_HEAD`, `rebase-merge/`, or `rebase-apply/` in `gitDir`. Show a banner
and a read-only conflict section with no stage/discard buttons.

## Errors

Every `GitResult.Failure` reaches the UI with its original `stderr`. No silent catch. A Git failure
must never close the app or leave a hung process.

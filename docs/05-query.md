# 05 — Query

Parent: [SPEC.md](SPEC.md). Contracts: [02-contracts.md](02-contracts.md).

The user-facing filter system is branch-level and lives in [12-filters.md](12-filters.md). The
grammar below remains the lower-level query layer; it is not the Filters dialog.

## Manual refs

Panel grouped as Locals / Remotes / Tags with one checkbox per ref, plus global toggles for Remotes,
Tags, and HEAD. Persist `hiddenRefs: Set<String>` (full ref names).

## Grammar

Terms are separated by spaces; quoted values may contain spaces.

| Term | Meaning | Effect |
|---|---|---|
| `ref:<glob>` | include matching refs | ref selection |
| `-ref:<glob>` | exclude matching refs | ref selection |
| `author:<text>` | author | `--author` (flat) |
| `msg:<text>` or bare word | message | `--grep` (flat) |
| `since:<date>` / `until:<date>` | Git date syntax | `--since` / `--until` (flat) |
| `path:<path>` | commits touching path | `-- <path>` (flat) |

`glob`: `*` matches any sequence including `/`; `?` matches one character. Match against
`shortName` (`feature/x`, `origin/main`, `v1.2`). Example:
`ref:origin/* -ref:origin/dependabot/*`.

`branch:<glob>` may later alias `ref:<glob>`; it must mean ref selection, not “commits containing the
branch”. `is:merge` is also deferred. Do not add either in the MVP without a decision.

## Resolution

1. Start with refs of enabled kinds (locals always on).
2. If `ref:` exists, keep refs matching at least one.
3. Remove refs matching any `-ref:`.
4. Remove `hiddenRefs`.
5. `LogSpec.revs` = remaining full names; `includeHead = showHead`.

No revs and no HEAD → empty graph with “No visible refs”. A syntax error keeps the last valid
query and shows the error; never lose the current view.

## Flat mode

Any content filter sets `flat = true`; see [04-graph.md](04-graph.md). This is deliberate: a content
filter can remove commits that connect the remaining ones, so drawing lanes would lie.

## Timing

Apply on Enter or after 300 ms without typing. Invalid text is shown in the bar but not applied.

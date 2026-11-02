# 04 — Graph

Parent: [SPEC.md](SPEC.md). Contracts: [02-contracts.md](02-contracts.md).

## Input

`RawCommit` in `git log --topo-order` order (children before parents). Rows `0..n-1`; one commit per
row. The engine is pure, deterministic, incremental, and independent of Compose/I/O.

## Lane algorithm

State: `lanes: ArrayList<Lane?>`, where `Lane(awaits: String, color: Int)` means the column is
waiting for that commit. `null` is free. Multiple columns may await the same hash; they converge at
the parent.

```kotlin
fun process(c: RawCommit) {
    val incoming = lanes.indices.filter { lanes[it]?.awaits == c.hash }
    val node = if (incoming.isEmpty()) firstFreeSlot() else incoming.min()
    val color = if (incoming.isEmpty()) nextColor() else lanes[node]!!.color
    val segs = mutableListOf<Seg>()

    lanes.forEachIndexed { i, lane ->
        if (lane == null) return@forEachIndexed
        if (i in incoming) segs += Seg(IN, i, node, lane.color)
        else segs += Seg(PASS, i, i, lane.color)
    }
    incoming.forEach { lanes[it] = null }

    if (c.parents.isNotEmpty()) {
        lanes[node] = Lane(c.parents[0], color)
        segs += Seg(OUT, node, node, color, merge = false)
    }
    for (p in c.parents.drop(1)) {
        val existing = lanes.indexOfFirst { it?.awaits == p }
        if (existing >= 0) {
            segs += Seg(OUT, node, existing, lanes[existing]!!.color, merge = true)
        } else {
            val j = firstFreeSlot()
            lanes[j] = Lane(p, nextColor())
            segs += Seg(OUT, node, j, lanes[j]!!.color, merge = true)
        }
    }

    trimTrailingNulls(lanes)
    maxLanes = max(maxLanes, lanes.size, node + 1)
    emitRow(node, color, c.parents.size, segs)
}
```

- `firstFreeSlot()`: leftmost free column, or append one.
- `nextColor()`: incrementing counter; UI maps `color % palette.size`.
- Missing parents leave dangling lanes; not an error.
- Row `r` depends only on earlier rows.
- **Trade-off:** the first parent always continues in the child's column, even if another column
  already awaits it. This can draw an extra lane until convergence. It preserves I4 and is accepted
  for MVP; joining an existing lane is post-MVP.

## Geometry

Row height `H`; column `l` at `x = l * W + W / 2`.

| Segment | Stroke |
|---|---|
| `PASS(l)` | `(x(l), 0) → (x(l), H)` |
| `IN(from → node)` | `(x(from), 0) → (x(node), H/2)`; straight if equal, S-curve otherwise |
| `OUT(node → to)` | `(x(node), H/2) → (x(to), H)` |

`OUT(merge = true)` has a small arrow at its lower end. Direction is controlled by
`ARROW_TOWARD_PARENT` in `GraphStyle.kt`.

## Invariants

- **I1** Every input commit appears once, in order, with `0 <= nodeLane < maxLanes`.
- **I2** Segments alone reconstruct exactly the input edge set `{(child, parent)}`.
- **I3** Columns (with colors) crossing the bottom of row `r` equal those crossing the top of
  `r+1`.
- **I4** First parent keeps the child's column (`OUT` with `from == to`, `merge = false`);
  additional parents are `merge = true`.
- **I5** One-shot and random-batch processing produce identical layouts.
- **I6** A commit with no visible child opens a new column; a commit with no parents emits no
  `OUT`.
- **I7** For every edge from child `r_c` to parent `r_p > r_c`, the target lane is alive in every
  intermediate row and enters the parent. No edge points at a disappearing lane.

## Storage

- Chunks of 4,096 rows. A new snapshot copies the chunk-reference list plus the open chunk.
- `RowChunk` uses parallel arrays: `hashes`, `authors`, `times`, `subjects`, `parentCounts`,
  `nodeLanes`, `nodeColors`, `segStart`, `segs`.
- Parent hashes are not kept after layout; detail requests go to `commitDetail`.
- Segment layout in a `Long`: `[from:16 | to:16 | color:8 | kind:2 | merge:1]`. Test the codec.
- Hide the encoding behind `SegmentsView`; the UI must not see it.
- Memory budget and the only allowed over-budget optimization: [13-performance.md](13-performance.md).

## Flat mode

When `LogSpec.flat` is true (author, message, date, or path filter), use `flat = true`: one column,
no lanes, no edges. The UI shows “Flat view (content filters active)”. Filtering commits by content
leaves parents outside the set, so a lane graph would be misleading.

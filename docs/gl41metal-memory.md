# GL41Metal geometry memory

`TerrainResources` owns one effective geometry capacity for the Java allocator and the GL texture-buffer mirror. `voxy.gl41metal.geometryCapacityMb` requests 256 MiB by default (16–2048 MiB); the effective capacity is capped by `GL_MAX_TEXTURE_BUFFER_SIZE`, with one 8-byte geometry element per texel. `voxy.geometryBufferSizeOverrideMB` configures the GL46 path.

Metal owns visibility, LOD selection and eviction eligibility. Java supplies resource pressure, applies the existing `NodeManager` subtree lifecycle and transfers geometry to GL. Neither Sodium near-section signals nor an independent CPU visibility calculation determine distant residency.

## Admission and recovery

The allocator checks contiguous space, including reusable interior holes, before a result can change section metadata or release its old mesh. Same-sized or smaller replacements can reuse their allocation at full capacity. A result that cannot fit remains in the bounded handoff for a later attempt. Section-id capacity is checked at the same admission boundary. Cancellation or region unloading causes obsolete results to be released through the normal node-result path.

At 80% geometry occupancy, or when an upload cannot fit, Metal stops admitting new traversal requests and increases its projected-area LOD threshold by a factor of 2.25 at most once per 60 rendered frames. At less than 40% occupancy it gradually restores detail, at most once per 300 frames. This hysteresis retains coarse coverage while preventing immediate eviction/rebuild oscillation. Near-volume exclusion still follows the existing Metal classification.

The traversal records the last frame in which each node needed its children. Under pressure a Metal pass produces at most 256 candidate positions per frame, excluding nodes that currently need finer LOD or needed it in the previous 30 frames. Java only collapses a candidate if its coarse mesh is ready. The existing hierarchy removal releases child meshes, cancels child requests and removes watches; a later Metal request may expand the node again. Bit 6 of the packed node flags indicates nonempty child availability so empty root leaves never request empty expansions.

## Bounds and synchronization

- The completed-result queue coalesces positions and accounts for geometry, occupancy and a fixed entry allowance. Its byte budget is one quarter of the geometry capacity, clamped to 16–64 MiB. Results that free or replace existing geometry may pass a blocked new allocation, avoiding head-of-line deadlock. Each active meshing worker may additionally own one result (the factory's geometry scratch buffer is 4 MiB).
- The existing worker scheduler pauses mesh generation before the handoff fills. Producers already generating a mesh wait for queue space; closing the queue wakes them and frees their result. Closing the CPU sync host precedes joining meshing workers. Obsolete queued build tasks are discarded before meshing.
- Traversal request admission is capped at 64 per frame and shrinks as the build queue approaches 512 tasks. Dirty rebuilds retain the existing service's position coalescing. Top-level additions pause at the same task threshold. Pending top-level and child-state changes coalesce by position rather than retaining multiple world-section references.
- Geometry uploads are limited to 2 MiB per frame plus the final individual mesh, and 384 results. Node-usage storage is four bytes per configured node (8 MiB at the default node limit); candidate storage is 2052 bytes per slot.
- Before mutating shared Metal hierarchy data, the backend checks that prior Metal submissions have completed. After a bounded-wait timeout it skips another submission until mutation is safe. GL mirror writes occur after previous opaque and translucent sampling commands in the same GL context; GL command ordering protects reused ranges. Slot retirement continues to use the existing GL fences.

The F3 hierarchy diagnostics show geometry usage, completed-result bytes, pending builds, deferred admissions and collapsed subtrees. Terrain diagnostics include the LOD area multiplier and traversal request budget. `-Dvoxy.gl41metal.logGeometryBudget=true` also logs hierarchy counters every five seconds.

## Verification

`./gradlew :fabric:geometryBudgetTest` runs the real allocator, node hierarchy and native-memory handoff without creating a GL context. It covers fragmentation, failed replacement without mutation, in-place replacement at full capacity, complete and partial subtree cancellation, late results, re-expansion and shutdown while a producer waits.

For a change confined to the shared backend, a short client smoke test on one loader is sufficient; repeat loader-specific runtime checks when changing that loader's integration or packaging. Copy a populated development world and use only the copy. An optional development mod can exercise a longer recovery sequence:

```sh
./gradlew -PgeometryValidation -PquickplayWorld='Issue5 Validation' -Dvoxy.renderBackend=gl41metal -Dvoxy.gl41metal.geometryCapacityMb=16 -Dvoxy.gl41metal.logGeometryBudget=true :fabric:runClient
```

The driver moves and rotates the player, changes Sodium render distance and Voxy subdivision/distance, recreates the renderer, captures actual framebuffer screenshots, and logs FPS plus facade diagnostics every 15 seconds. It exits normally after three minutes; use `-Dvoxy.validation.durationSeconds=300` for five minutes. The same driver is available through `:neoforge:runClient` when needed. Inspect screenshots and verify nonzero distant draws, exercised recovery at small capacity, bounded queues, continued streaming after movement/recreation and successful shutdown. FPS while generating new terrain is not a steady-state performance benchmark.

The `validation` source set is only enabled by `-PgeometryValidation`. It is a separate development mod and is not included in either release jar.

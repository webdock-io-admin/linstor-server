Title: drbd: skip the no-op set-gi calls for other node-ids on new volumes

Branch: webdock-io-admin/linstor-server `pr/linstor-server-20-set-gi-once` (a1e24b53f code + tests, 04a3249f3 CHANGELOG), on master e1d99946e.

---

Refs #565 (first half: 32 `drbdmeta set-gi` calls per new volume).

**Problem.** For every new DRBD volume whose metadata gets a GI, `DrbdLayer.createMetaData()` runs `drbdmeta --force --node-id <N> <minor> v09 <dev> internal|flex-external set-gi <gi>` for every node-id 0..`NODE_ID_MAX`, sequentially. That is 32 execs per volume. On our storage nodes at ~940 datasets, each exec took ~156 ms (p50), so ~5 s per volume and about half of the satellite's exec time during creates.

**Why most of them are no-ops.** Two branches set `runSetGi`:
- *Local satellite won the UpToDate race:* the other node-ids get `withBitmapBaseDataUUID(day0)`. The bitmap UUID is stored per peer slot, so every node-id needs its own call. This branch is unchanged.
- *Skip initial sync* (`DrbdLayerUtils.skipInitSync()`): the other node-ids get only `withCurrentUUID(day0)`, i.e. `<day0>:::::`.

In drbd-utils (`m_set_v9_uuid()`, 9.35.0), the current UUID, history UUIDs and device flags are stored device-wide. Only the bitmap UUID and the peer flags go into `peers[node_id]`. Empty fields leave the stored value unchanged (`m_strsep_u64()`, `m_strsep_u32()`, `m_strsep_bit()`), and `meta_set_gi()` has no other per-node-id effect. So in the skip-init-sync branch, the 31 calls for the other node-ids rewrite the current UUID that the local call writes, and nothing else.

**Change.**
- `DrbdGiStringBuilder.hasPeerSlotData()` returns true if the GI sets a per-slot field: the bitmap UUID or the peer-outdated bit. These are the only per-slot values the builder can emit. A comment asks future per-slot setters to extend it.
- `createMetaData()` runs set-gi for the other node-ids only if `otherNodeIdGiBuilder.hasPeerSlotData()`. The local node-id always gets its call.
- Result: skip-init-sync volumes get 1 call instead of 32. Race winners keep 32.

**Tests.**
- New `DrbdGiStringBuilderTest` cases: `currentUuidOnlyHasNoPeerSlotData`, `bitmapUuidIsPeerSlotData`, `peerOutdatedFlagIsPeerSlotData`.
- `./gradlew :satellite:test` and `./gradlew :test` pass; `:satellite:checkstyleMain` is clean.
- Dev cluster (6 nodes, DRBD 9.3.4, ZFS_THIN, `UseZFSClone`), on LINSTOR 1.35.3 with this change on the storage nodes:
  - metadata identical (`drbdmeta … dump-md`, day-0/random UUIDs normalised) for a skip-init-sync volume created by the stock and the patched satellite: <result>
  - `set-gi` execs per skip-init-sync volume: 32 → <n>; per race-winner volume: <n> (expected 32)
  - create-rate cell, stock vs patched: <numbers>

---

Prepared with AI assistance at Webdock; reviewed and tested by <engineer>

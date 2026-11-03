Title: Satellite: DRBD tooling cost per DeviceManager cycle grows with the number of resources (whole-node drbdsetup status / sh-list-adjustable / adjust), and 32 drbdmeta set-gi calls per new volume

**Environment:** LINSTOR 1.35.2 satellite (same code in v1.35.3 and on master e1d99946e) with #561 applied, so ZFS listings are scoped to the datasets the satellite acts on. DRBD 9.3.4, drbd-utils 9.35.0, ZFS_THIN, `UseZFSClone`. One storage node, measured with `strace -f -e trace=execve` (with timing) across a create ramp, at 492 and at 936 datasets on the node.

**What grows is the DRBD userspace tooling, not ZFS.** The `zfs list`/`zfs get` calls stay at 14-15 ms each.

Per DeviceManager cycle (mean):

| | 492 datasets | 936 datasets |
|---|---|---|
| `drbdsetup` total | 3.33 s | 6.19 s |
| `drbdmeta` total | 3.00 s | 4.42 s |
| `drbdadm` total | 1.83 s | 3.57 s |

Per call (p50), and calls per 100 creates:

| Call | 492 | 936 | Calls per 100 creates |
|---|---|---|---|
| `drbdsetup status` (all resources) | 650 ms | 1,464 ms | ~130 |
| `drbdadm sh-list-adjustable all` | 607 ms | 1,217 ms | ~130 |
| `drbdadm -vvv adjust <rsc>` | 513 ms | 1,027 ms | ~300 |
| `drbdsetup dstate` (run by `drbdadm adjust`) | 73 ms | 130 ms | ~3,700 |
| `drbdmeta … set-gi` | 101 ms | 156 ms | 3,200 (32 per volume) |

**1. set-gi runs 32 times per new volume.** `DrbdLayer.createMetaData()` runs `drbdmeta --force --node-id <N> <minor> v09 <dev> internal set-gi <gi>` for every node-id 0..`NODE_ID_MAX` (DrbdLayer.java, the loop after `if (runSetGi)`; v1.35.3 lines 1597-1606). At ~940 datasets that is 32 × ~156 ms ≈ 5 s per volume, about half of the satellite's exec time in our create runs.

In the skip-initial-sync branch (`DrbdLayerUtils.skipInitSync()`), the GI for the other node-ids is `<day0>:::::`, i.e. only the current UUID. In drbd-utils 9.35.0, `m_set_v9_uuid()` stores the current UUID, the history UUIDs and the device flags device-wide. Only the bitmap UUID and the peer flags go into `peers[node_id]`. An empty field leaves the value unchanged (`m_strsep_u64`, `m_strsep_bit`), and `meta_set_gi()` has no other per-node-id effect. So in that branch, the 31 calls for the other node-ids write the same current UUID the local call writes, and nothing else. In the branch where the local satellite won the UpToDate race, the other node-ids get the day-0 bitmap UUID, which is per slot, so those calls are needed.

We have a small patch that skips the other node-ids when their GI carries no per-slot data (a `DrbdGiStringBuilder.hasPeerSlotData()` check, with tests). We can open it as a PR if you agree with the approach.

**2. Whole-node calls per cycle.** `DrbdLayer.prepare()` runs `drbdsetup status` for all resources (`DrbdAdm.drbdSetupStatus()`) and `drbdadm sh-list-adjustable all` (`DrbdAdm.listAdjustable()`, with the comment "drbdadm will read all files anyway"). Each `drbdadm -vvv adjust <rsc>` parses the whole configuration, because `-c` is commented out in `DrbdAdm.adjust()`. All three are linear in the number of resources on the node, and they run on every cycle, including cycles that touch a single resource.

**Questions:**
1. Is the set-gi reduction for the skip-init-sync branch acceptable? Should we open the PR?
2. For the whole-node calls: would you accept restricting `drbdsetup status` to the resources of the cycle, and `sh-list-adjustable` to those resources? Or a per-resource config (`drbdadm -c`) for `adjust`? Or is there a reason they must cover all resources?

Related: #561 (ZFS listings), #562 (clone bursts: about 8 DeviceManager cycles per clone, and the cycle cost grows with the object count).

---

Prepared with AI assistance at Webdock

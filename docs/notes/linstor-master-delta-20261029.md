# linstor-server v1.35.2 → master: what touches our plans

Range: v1.35.2 (4f9bfb0a3, our rack build) .. master e1d99946e (= v1.35.3, 2026-10-21), 74 commits, read 2026-10-29.
Read-only notes; one line per relevant commit. Not listed: dependency bumps, CI, packaging, docs, web UI, DB/K8s migrations.

## ZFS provider and listings (fix 16, design 19)
- dfa818335 Jersey 4 / jakarta: only import changes in ZfsProvider/ZfsThinProvider. Fix 16 is unaffected and applies to both v1.35.2 and master unchanged (built and tested on both).
- No commit changes `zfs list` / `zfs get`, genericExecutorLimiter or the ZFS cache: the empty-batch and pool-wide-get bugs are still on master.
- cfd794924, 252e4e6e9, 1985e194b, 81f1c0ffe, e732702c6 LVM-thin DiscardOnDelete / VDO: LVM-thin only, nothing for ZFS. Adds `Commands.discard` next to genericExecutorLimiter (no conflict with fix 16).

## Clone / CF_ snapshots (design 18, report 17)
- 4d5221f4b SuspendLayerUtils: snapshots and clones suspend IO only on the topmost layer (+ cache layers). Before, a LUKS layer below DRBD was also suspended, which with DRBD 9.3 stalled snapshots/clones until ko-count. Relevant if any of our resource groups use LUKS; harmless otherwise.
- No change to CF_ snapshot creation/cleanup, UseZFSClone or the clone 500 body (mapToCloneStarted unchanged since 9d96b8001); report 17 and design 18 still apply.

## Deletes and the staged rd delete (fixes 11/12, #551)
- 1d61ce958 Rsc delete: flags are re-checked in each delete stage (fixes a race between a resource delete and a concurrent restore/un-delete that made satellites shut down with a UUID mismatch). Touches CtrlRscDeleteApiHelper; our PRs #557/#558 are satellite-side and do not conflict.
- 77e770f3e Delete/deactivate/rollback are rejected while the DRBD device is open on a Secondary (`open` in resource state). Incus deletes of a disk still open somewhere now fail fast instead of half-completing; worth knowing for Incus error handling.

## Ports, paths, node connections (two-path tests)
- 87450b150 FullSync keeps node-connection paths even when the satellite shares no resource with the other node. Before, a reconnect dropped them and new resources fell back to the default interfaces. Directly relevant to our two-path (leaf A/B) setup on 1.35.2: after a satellite reconnect, check that new resources still get both paths.
- No change to TcpPortAutoRange / port allocation.
- dcd001f17 .res file: the peer-device options (`disk` section: c-max-rate, c-fill-target, …) now include controller- and node-connection-level properties when a resource-connection exists. On 1.35.2 such options set on the controller may be missing from .res files of resources with resource-connections; relevant to our resync-rate settings (client PR #60 sets them per rg).

## Tie-breaker, auto-place, auto-diskful
- a6c698fe2 `DrbdOptions/auto-diskful` settable on nodes and resources, `-1` disables it (1.35.3).
- 3dee007ee BalanceResourcesTask skips a resource definition while any of its resources is in a transitional state; fixes the task recreating a resource the user just deleted.
- 0d6ad825f REST: resource groups now return `storage_pool_diskless_list` and `node_name_list` (were settable, never returned).
- 9b80e56f6 Invalid layer stacks are rejected at rg create/modify and rd modify instead of at spawn time.

## DeviceManager / satellite behaviour
- 8aabda5a5 Fixes a never-ending storage pool update loop (constant DeviceManager cycles, high CPU) with LVM PVs on partitions. LVM only; not the cause of our ZFS idle cycles.
- c707347d1 A failed `drbdadm adjust` error report now contains the regenerated .res file. Useful for our DRBD incident analysis on 1.35.3.
- f50c706c3 ConcurrentModificationException in SatelliteVolumeState (metrics / resource list) fixed.
- 47613ea8e Satellite publishes the controller address in /run/linstor/linstor-client.conf.

## Upgrade note
All of the above are in v1.35.3. Our dev jars are 1.35.2-based. If the rack moves to 1.35.3, rebuild dev-16 on v1.35.3 (fix 16 applies unchanged; #545 = PR #554 also applies to master).

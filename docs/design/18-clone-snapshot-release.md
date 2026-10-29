# Design: fewer CF_ clone snapshots on ZFS (finding 5, ask 3)

Status: draft for discussion, 2026-10-29. Not implemented. References are to
master e1d99946e.

## Problem

With `UseZFSClone` (resource group property, or `use_zfs_clone` in the clone
request), every `resource-definition clone` keeps one snapshot of each source
volume for the clone's whole life:

- `AbsStorageProvider.createCloneSnapshots` (`AbsStorageProvider.java:633`)
  calls `ZfsProvider.createSnapshotForCloneImpl` (`ZfsProvider.java:1001-1030`).
  That creates `<src>@CF_<hash(cloneRscName_vlmNr)>`
  (`AbsStorageProvider.getCloneSnapshotName`, `:595-599`), sets
  `linstor:clone_for=<clone>` and `linstor:marked_for_deletion=true`.
- `CloneService.startClone`, case `ZFS_CLONE` (`CloneService.java:441-453`),
  runs `zfs clone` from that snapshot (`doZFSClone` → `ZfsCommands.restoreSnapshot`).
- After the clone, `doZFSCloneSourceCleanup` / `doZFSCloneTargetCleanup`
  (`CloneService.java:201-238`) skip the delete for `ZFS_CLONE`, because ZFS
  cannot destroy a snapshot with a dependent clone. The snapshot goes when the
  last clone is deleted: `ZfsProvider.deleteCascade` → `markedForDeletion &&
  canDelete` (`ZfsProvider.java:460-475, 555`).

So N clones of one image cost N snapshots per image volume, all hidden from
`linstor snapshot list`. Every pool-wide ZFS scan visits them, and they are
never owned, so they are always cold (see the fix-16 PR for the cost).
Measured on a 6-node dev cluster: 51 clones of one image = 51 snapshots on
each of its volumes.

## Options

### A. `zfs promote <clone>` once the clone is UpToDate: rejected

`zfs promote` swaps the origin relationship. The origin snapshot, and every
older snapshot of the source, moves to the promoted clone, and the source
volume becomes a clone of it. Other clones of the moved snapshots are
re-pointed to the promoted clone. Nothing is released; the dependency only
changes direction. With many clones of one image, the image then depends on
whichever clone was promoted last, and that clone becomes undeletable while
the image exists. It also changes `origin` under LINSTOR's
`ZfsInfo.originStr` and `clones` bookkeeping, and DRBD has the clone open
during the promote. This is not a fit for LINSTOR's clone model.

### B. Full copy instead of a ZFS clone: exists today

`UseZFSClone=false` (the default) selects `ZFS_COPY`: `zfs send --embed
--large-block <src>@CF_… | zfs receive -F <dst>` (`CloneService.java:615-630`),
then `doZFSCloneSourceCleanup` destroys the CF_ snapshot right after the copy.
It is already settable per resource group (`ApiConsts.KEY_USE_ZFS_CLONE`,
`CtrlRscDfnApiCallHandler.java:1567-1571`).

Cost: no block sharing. Each clone uses the image's full referenced size and
copy time (size / send-receive throughput, plus the DRBD initial sync for the
other replicas as with any new data). Use it where space is cheap and the
clone count per image is high.

### C. Share one CF_ snapshot per unchanged source volume: recommended

If the source volume has not been written since its newest CF_ snapshot
(`zfs get -Hp -o value written@<snap> <src>` = 0), the next clone reuses that
snapshot instead of creating its own. For images, which are read-only
templates, all N clones then share one snapshot per image volume (or one per
image revision).

- **Deletion needs no new code.** The shared snapshot keeps
  `marked_for_deletion=true`. `deleteCascade` already destroys it only when
  `canDelete` (no clones left), and `zfs destroy` refuses earlier anyway.
- **Naming:** the snapshot name can no longer be derived from the clone name,
  because `startClone` recomputes it via `getZFSCloneSnapshotNameFull(src,
  cloneName, vlmNr)` (`CloneService.java:445-448`). Use a source-derived name
  instead, e.g. `CF_s<createtxg>` of the volume, or `CF_` + a counter. Hand the
  chosen name from `createSnapshotForCloneImpl` to `startClone`. Both run on
  the same satellite, because `zfs clone` needs the same pool, so an in-memory
  map keyed by (source volume, clone resource) is enough. Fall back to
  recomputing the choice ("newest CF_ with written=0") after a restart. Keep
  `linstor:clone_for` as a multi-valued marker, or drop it: the clones are
  already visible in the snapshot's `clones` property.
- **Hooks:** `ZfsProvider.createSnapshotForCloneImpl` (choose or reuse),
  `CloneService.startClone` case `ZFS_CLONE` (use the chosen name),
  `ZfsProvider.canDelete` / `deleteCascade` (unchanged). The 2024-11-21
  migration (`StltMigration_2024_11_21_CreateSnapshotsFromZfsClones`) and the
  existing per-clone snapshots stay as they are; only new clones share.
- **Option:** `StorDriver/ZfsShareCloneSnapshot` (resource group, controller
  or storage pool level; default off at first), so behaviour only changes
  where an admin enables it.
- **Cost:** one `zfs get written@…` per clone and volume. No extra space, and
  fewer snapshots: N → 1 per unchanged image volume, so the dataset count
  each scan walks drops by N-1.
- **Risk to measure:** whether a DRBD-backed source that is idle and
  Secondary stays at `written=0`. DRBD internal metadata lives on the same
  zvol, and activity-log or bitmap writes would break the reuse condition
  (falling back to today's behaviour, never to wrong data).

### D. ZFS clone now, background full copy and swap later: rejected

A clone could start as a `zfs clone` and later be converted to an independent
copy (send/receive into a new zvol, then swap it under DRBD). The swap needs
the DRBD device to detach and reattach a different backing device while in
use, with LINSTOR changing the volume's identifier. That is far more risk
than the space saving is worth.

## Existing alternative to check first

Clones made by `snapshot resource restore` from one ordinary LINSTOR snapshot
of the image (`ZfsCommands.restoreSnapshot`, a `zfs clone` from that
snapshot) already share a single snapshot. If Incus can create its instances
that way instead of with `resource-definition clone`, the snapshot count drops
without a LINSTOR change. To verify: how LINSTOR handles deleting that
snapshot while restored resources still depend on it.

## Questions for LINBIT

1. Is sharing CF_ snapshots across clones acceptable (option C), and which
   naming do you prefer?
2. Is `zfs promote` used or planned anywhere in LINSTOR's ZFS clone or restore
   path (we found none on master)?
3. Is `snapshot resource restore` the recommended way to stamp out many
   volumes from one image on ZFS?

---

Prepared with AI assistance at Webdock; reviewed by <engineer>

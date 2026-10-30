Title: ZFS clones (UseZFSClone): every clone keeps its own hidden CF_ snapshot of the source for its whole life; could clones of an unchanged source share one?

**Environment:** LINSTOR 1.35.2 (same code on master e1d99946e), ZFS_THIN pools, resource group `UseZFSClone=true`, Incus creating VM disks with `resource-definition clone` from image resources.

**What happens:** every `resource-definition clone` with `UseZFSClone` creates a snapshot `<src>@CF_<hash(clone name)>` of each source volume (`ZfsProvider.createSnapshotForCloneImpl`, `ZfsProvider.java:1001-1030`; name from `AbsStorageProvider.getCloneSnapshotName`, `:595-599`), sets `linstor:clone_for` and `linstor:marked_for_deletion`, and runs `zfs clone` from it (`CloneService.startClone`, `:441-453`). ZFS cannot destroy a snapshot that has a clone, so `doZFSCloneSourceCleanup` skips it (`CloneService.java:201-222`), and it goes only when the last clone is deleted (`ZfsProvider.deleteCascade`, `markedForDeletion && canDelete`).

Result on a 6-node dev cluster: 51 VMs cloned from one image = 51 `CF_` snapshots on each of the image's volumes, none of them visible in `linstor snapshot list`. At our target scale (thousands of VMs from a few images) that is thousands of snapshots per storage node. OpenZFS never caches snapshot metadata (they are never owned), so every listing that includes them reloads them; #561 removes the pool-wide listings, but listing the image volume (`zfs list -r <image volume>`) still walks all of its snapshots.

**Options we looked at:**

- `zfs promote` of the clone once it is in sync: does not release anything. The origin snapshot moves to the clone and the source becomes dependent on it; with many clones of one image, the image ends up pinned to the last promoted clone.
- Full copy (`UseZFSClone=false`, send/receive, the CF_ snapshot is destroyed right after): exists today, but gives up block sharing and costs a full copy per clone.
- **Share one CF_ snapshot per unchanged source volume:** if the source has not been written since its newest CF_ snapshot (`zfs get -Hp -o value written@<snap> <src>` = 0), the next clone reuses that snapshot instead of creating its own. For read-only images, N clones then need one snapshot per image volume. Deletion needs no new logic (the shared snapshot keeps `marked_for_deletion`; `canDelete` already waits for the last clone). The snapshot name could no longer be derived from the clone name, so `startClone` would need the chosen name from `createSnapshotForCloneImpl`. It could be opt-in per resource group or storage pool.

**Questions:**
1. Is sharing the CF_ snapshot across clones of an unchanged source acceptable to you, and if so, which naming would you prefer?
2. Or is `snapshot resource restore` from one ordinary LINSTOR snapshot of the image the recommended way to create many volumes from one image on ZFS (restores also `zfs clone`, from that one snapshot)? If so, how is that snapshot's deletion handled while restored resources still depend on it?

Related: #561 (satellite ZFS listings).

---

Prepared with AI assistance at Webdock

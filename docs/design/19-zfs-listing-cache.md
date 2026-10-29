# Design: a short-lived ZFS listing cache on the satellite (follow-up to fix 16)

Status: draft, 2026-10-29. Not implemented. References are to
`fix/linstor-server-16-zfs-scoped-listing` (f68dc96b0, on master e1d99946e).
Depends on the measurements from the fix-16 rack test: build this only if the
remaining per-run listings still matter after fix 16.

## What remains after fix 16

Fix 16 removes the host-wide and pool-wide calls. What is left per device
manager run that touches ZFS resources:

| Call | Where | When |
|---|---|---|
| `zfs list -r … -t volume,snapshot <dispatched volumes, snapshot bases>` + `zfs get -r … marked_for_deletion <listed roots>` | `ZfsProvider.getInfoListImpl` → `ZfsUtils.getZfsList` (`ZfsProvider.java:144`) | every `prepare()`, i.e. every run that dispatches a ZFS resource or snapshot |
| same, for one dataset | `ZfsProvider.loadZfsInfo` (`:546`) | delete cascade, on a cache miss of `infoListCache` |
| `zfs list -r … <volumes>` (no property query) | `ZfsProvider.fetchAllocatedSizes` (`:1014`) | controller queries of allocated space (resource list, autoplacer, metrics) |
| `zfs get available` | `getFreeSpacesImpl` (`:138`) | end of a run if a storage pool changed |
| `zfs get quota`, `zpool get size`, `zfs get available` / `zfs list -d 0 -t filesystem` | `getSpaceInfo` (`:769`, thin: `ZfsThinProvider`) | free-space queries |
| `zfs get volblocksize` | `getZfsExtentSize` (`:379`, `:869`) | volume grown beyond the expected size; extent-size checks |

The trace showed about 13 runs per idle minute (DRBD events dispatch their
resource). After fix 16 each of those lists only the dispatched resources and
their snapshots, so a cache saves the per-run fork/exec and a few datasets'
reads, not the pool walk. The rack measurement shows whether that is still
worth it.

## Precedent: the LVM cache

`LvmUtils` (`satellite/.../lvm/utils/LvmUtils.java`) already caches `lvs` and
`vgs` output in a `TimedCache` (`server/.../utils/TimedCache.java`):

- default 10 s, configurable per node or controller with
  `StorDriver/SizesCacheTime` (`ApiConsts.KEY_STOR_POOL_LVM_SIZES_CACHE_TIME`,
  `LvmUtils.updateCacheTime`, `:276-293`, called from
  `StltApiCallHandler.java:653` and `StltNodeApiCallHandler.java:189`);
- `LvmUtils.recacheNext()` / `recacheNextLvs()` (`:296-311`) clear it, and
  every LVM-changing path calls them (43 call sites, e.g. `CloneService` after
  a copy).

The ZFS cache should follow the same pattern, so it reads as familiar code.

## Proposal

1. **Cache:** `ZfsUtils` gets a `TimedCache<String /* zpool */, Map<String,
   ZfsInfo>>` holding the result of
   `zfs list -r -H -p -o name,refer,volsize,type,volblocksize,origin,clones -t volume,snapshot <zpool>`
   plus the `marked_for_deletion` query on the same pool. One pool-wide
   listing per pool **at most once per cache period**, instead of per-run
   scoped listings. `getZfsList(datasets)` answers from the cached map:
   entries for the requested datasets and their snapshots.
2. **Period:** default 10 s like LVM. Either reuse `StorDriver/SizesCacheTime`
   or add `StorDriver/ZfsListCacheTime`
   (node / controller, ms), `0` = off, which is today's behaviour with fix 16.
3. **Invalidation (`ZfsUtils.recacheNext(zpool)`)** after every command that
   changes the listing:
   - `ZfsCommands.create`, `resize`, `delete`, `rename`, `createSnapshot`,
     `createSnapshotFullName`, `restoreSnapshot` (`zfs clone`), `rollback`,
     `setUserProperty` (`marked_for_deletion`, `clone_for`);
   - `CloneService` after a `ZFS_COPY` (send/receive) and after the `ZFS_CLONE`
     cleanup;
   - backup shipping: after the receiving `zfs receive` and the sending side's
     snapshot handling (`getBackupShippingReceivingCommandImpl`, `:802`);
   - `createTmpProbeVlm` / `deleteTmpProbeVlm`;
   - on any `StorageException` from a ZFS command (the cache may be wrong).
   Not needed after `hideUnhideSnapshotDevice` (`snapdev` is not listed).
4. **Explicit refresh:** clear the ZFS cache wherever the LVM cache is
   cleared today: `StltApiCallHandlerUtils.clearCaches` (`:353-356`), the layer
   data mergers (`StltLayerRscDataMerger.java:437`,
   `StltLayerSnapDataMerger.java:400`, there guarded by `hasLvm`) and
   `DevicePoolHandler` (storage pool create/delete). Whether a full sync or
   `node reconnect` reaches `clearCaches` should be checked; if not, add it.
5. **Not cached:** the `refer` column changes with every write, so
   `fetchAllocatedSizes` (what the controller shows as allocated space) keeps
   its own fresh, scoped `zfs list`. Free-space queries (`zfs get available`,
   `quota`, `zpool get size`) stay uncached as today.

Trade-off of point 1: with the cache on, the per-pool listing is pool-wide
again, but at most every 10 s instead of per run. That is cheaper only if runs
are more frequent than the period and the pool is not huge. On very large
pools (8K+ volumes, cold snapshots) a pool-wide scan every 10 s can cost more
than fix 16's per-run scoped listings. **Alternative 1b**: cache the scoped
results per dataset with the same period and invalidation. That avoids the
pool walk entirely, at the cost of a merge step and partial misses. The rack
curve (1K/2K/3K datasets, warm and cold) decides between 1 and 1b. If
neither wins clearly, the recommendation is to stop at fix 16.

## Staleness risk

Anything that changes the pool outside LINSTOR's own commands is invisible
for up to one period: an admin's `zfs destroy` / `zfs rename`, `zfs receive`
by tools other than LINSTOR's shipping, another LINSTOR satellite on a shared
pool (not supported for ZFS), snapshot tools (sanoid, zrepl). Consequences
within the period:

- a volume deleted outside LINSTOR still counts as existing. The next
  create/resize fails, which invalidates the cache through the exception
  rule, and the following run is correct;
- an externally created dataset at LINSTOR's name stays unseen for up to
  10 s. That is the adoption case of #551 / fix 12, and it only delays
  detection by one period;
- `marked_for_deletion` set externally is a non-case.

No path can destroy data because of a stale entry: `zfs destroy` of a
missing dataset fails, and a dataset that appeared after caching is not in
the cache, so LINSTOR does not act on it. With the period at `0` the
behaviour is exactly fix 16's.

## Tentative size

- `ZfsUtils`: `TimedCache`, `recacheNext`, `updateCacheTime`, cache lookups
  in `getZfsList`: about 120 lines.
- `ZfsCommands` (or the `ZfsProvider` call sites): about 15 `recacheNext`
  calls; `CloneService` and backup shipping: about 6.
- property constant, `updateCacheTime` wiring next to
  `LvmUtils.updateCacheTime`, docs: about 30 lines.
- tests (TestExtCmd): cache hit within the period, expiry, invalidation after
  each mutating command, exception invalidation, period 0 = no cache: about
  200 lines.

About 350-400 lines in total, a medium PR. Propose it to LINBIT only after
fix 16 is merged and the rack numbers show the per-run listings still
matter.

---

Prepared with AI assistance at Webdock; reviewed by <engineer>

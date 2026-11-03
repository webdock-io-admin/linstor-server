# Fix 20 on node P (v3 jars): what to look for (Wednesday 08:30-09:30 CET)

## Which branch a new volume takes

- **Skip initial sync** (`DrbdLayerUtils.skipInitSync()`, e.g. thin/ZFS clones and new thin volumes): fix 20 applies. Expect 1 set-gi.
- **UpToDate-race winner** (the satellite that makes a new volume's first UpToDate replica): unchanged. Expect 32 set-gi.
- **Thick loser:** no set-gi at all, before and after.
- Both branches log `DRBD skipping initial sync for <rsc>/<vol>` after the set-gi loop, so that line alone does not tell the branches apart. Count the execs.

## Counting set-gi per volume

On P while creating:

```
strace -f -tt -e trace=execve -s 300 -p "$(pgrep -f 'Satellite' | head -1)" -o /tmp/execs.txt
grep -c 'set-gi' /tmp/execs.txt
grep 'set-gi' /tmp/execs.txt | grep -o '"--node-id", "[0-9]*", "[0-9]*"' | sort | uniq -c
```

The last line shows node-id and minor per call.
- With v2 or stock jars: 32 lines per minor, node-ids 0..31.
- With v3: 1 line per minor in the skip-init branch, carrying the local node-id. A race winner still has 32.

The GI argument is the last argv element:
- `<day0>:::::` means skip-init, other node-ids. These are the calls that disappear.
- `:<day0>::::` means race winner, other node-ids. These must still be there.

## Metadata equivalence

For one skip-init volume made by v2 and one made by v3, with the same source, before first use:

```
drbdmeta --force <minor> v09 <zvol> internal dump-md > /tmp/md-<v>.txt
```

Normalise the current and day-0 UUIDs, then diff. Expected: identical, including `peer[*]` blocks and flags. Any difference in `peer[...]` bitmap-uuid or flags is a fail: stop and roll back to v2.

## Risk at ~4K objects per node

- Fix 20 only removes exec calls. The metadata end state is the same by construction, which the dump-md check confirms. Nothing in it scales with the object count. It cuts ~31 × 150-200 ms per new skip-init volume at that scale.
- Not changed: the whole-node calls per cycle (`drbdsetup status`, `drbdadm sh-list-adjustable all`, `adjust` parsing the whole config). They still grow with the object count (#565, second half). At ~3,900 zvols per satellite, expect slower cycles from those, not from set-gi.
- If a set-gi fails, the volume creation fails with `Failed to create meta-data for DRBD volume …`, the same as before. With fix 20 there is no partial set of other-node-id writes before the failure.

## Satellite log

- `/var/log/linstor-satellite/linstor-Satellite.log`: `DRBD meta data created for <rsc>/<vol>`, then `DRBD skipping initial sync for <rsc>/<vol>`.
- Errors: `linstor err l --since 1h`. A set-gi failure shows as an ErrorReport with the `drbdmeta` command line.
- After a create, `drbdadm status <rsc>` should reach UpToDate on both storage nodes without a full initial sync, the same as with v2.

## Rollback

v2 jars (`dev-16-jars-1.35.3-v2`), same procedure as runbook step 3.

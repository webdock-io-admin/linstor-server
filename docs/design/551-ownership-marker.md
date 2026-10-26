# Design: persistent ownership marker for backing devices (#551)

Status: draft for discussion with the LINSTOR maintainers, 2026-10-26. Not
implemented. References are to master e1d99946e.

## Problem

The satellite treats any backing device that has the expected name as the
volume's own device. `ZfsProvider.updateState` sets `exists=true` for any
dataset listed by `zfs list`, and `AbsStorageProvider` then never calls
`createLvImpl`. A device left behind by a failed delete (#545), a hand-made
one, or one from an earlier incarnation of the same resource is adopted
silently:

- its properties (e.g. `volblocksize`) win over `StorDriver/ZfscreateOptions`;
- on thin storage, DRBD skipped the initial sync on it, so the replicas
  silently diverged (#551, fixed by `fix/linstor-server-11-adopted-device-sync`
  for non-winning replicas);
- deleting the volume destroys the adopted device, even though LINSTOR never
  created it.

Two follow-up branches (`-11-adopted-device-sync`, `-12-adopted-zvol-blocksize`)
tell "created by this satellite" from "adopted" with a transient flag. That
flag is lost on every satellite restart, so after a restart every existing
device looks adopted. That limits what can safely be refused (only new DRBD
metadata, only a `volblocksize` mismatch). It also leaves one gap: after a
restart, a per-node `resource delete` destroys a device LINSTOR had refused.

A persistent marker on the device itself closes these gaps.

## Rack evidence (2 diskful nodes, LINSTOR 1.35.2, DRBD 9.3.4, ZFS thin)

Adopted zvol created by hand at the name LINSTOR uses (`volblocksize` 8K),
volume definition with `StorDriver/ZfscreateOptions -b 16K`:

| Case | Adopted zvol | Without fix | With the transient flag (v2-v4 test builds) |
|---|---|---|---|
| a | exact size, empty | adopted, 8K kept, initial sync skipped, UpToDate | non-winner: warning, full resync, `drbdadm verify` 0 |
| b | smaller, empty | adopted, grown by `zfs set volsize`, otherwise as a | as a |
| c | exact size, 64 MiB random data | adopted, sync skipped, verify: 16,384 4 KiB blocks out of sync | non-winner: full resync, verify 0 |
| w | as c, on the race winner | as c | warning only, verify: 16,384 out of sync (known limitation) |
| -b 16K mismatch (v4) | as c, non-winner | adopted | refused once with an error report, later runs only warn; `resource-definition delete` completes in one call and leaves the foreign zvol untouched |

The first v4 predecessor (v3) also showed that a refusal must not fail every
run: the staged `resource-definition delete` updates the satellite before it
marks the resource, and repeated errors blocked it.

## Proposal

### Marker

At creation, the satellite writes a marker onto the device:

- ZFS: user property `linstor:owner=<value>` (the provider already writes
  `linstor:` user properties, e.g. `marked_for_deletion`, `clone_for`),
  set with `zfs create -o linstor:owner=...` so it exists from the start.
- LVM / LVM thin: tag `linstor_owner_<value>` via `lvcreate --addtag` (the
  provider already tags, e.g. `linstor_clone_snapshot`).
- Other providers (FILE, SPDK, EBS, Storage Spaces): out of scope at first.
  They keep today's behaviour.

`<value>`: the UUID of the LINSTOR volume (`Volume` object). Unlike
`<node-uuid>/<rsc>`, it changes when the resource is deleted and recreated,
so a leftover of an earlier incarnation is recognised as foreign, which is
exactly the #545/#551 case. The marker is read in the same `zfs list` / `lvs`
call the providers already make (one more column or `-o` property), so there
are no extra commands per cycle.

### Adoption

When a device with the expected name exists:

| Marker | Meaning | Action |
|---|---|---|
| equals this volume's UUID | ours | use it (today's behaviour) |
| other LINSTOR volume UUID | leftover of an earlier incarnation, or another volume's | refuse (default), or rename out of the way (`_foreign_<name>_<time>`, like `_deleted_`) and create a new device; configurable |
| none | hand-made, or created before the marker existed | see "Migration"; after migration: refuse |

A refusal is reported once and then only logged, as in branch 12, so it does
not block unrelated operations.

### Delete

Destroy only a device whose marker equals this volume's UUID. A device
without our marker is forgotten (`exists=false`, no destroy) and an info line
names it.

### Migration of existing volumes

Existing devices have no marker. Options:

1. A controller or node property, e.g. `StorDriver/OwnershipMarker` =
   `off` (default at first) / `mark` / `enforce`:
   - `off`: today's behaviour, markers are written on create only.
   - `mark`: one-time pass per storage pool. Every device that belongs to a
     volume that currently *has DRBD metadata or is otherwise known to be in
     use* is marked. Adoption and delete behave as today.
   - `enforce`: refuse and skip-destroy as above.
2. Alternatively, mark on the first successful DRBD adjust of a volume
   (proof that LINSTOR has been using it), which needs no flag but leaves
   storage-only resources unmarked.

The opt-in flag keeps existing clusters unaffected until an admin decides,
and gives a rollback path (`enforce` back to `mark`/`off`).

### Clones, snapshots, restore, shipping

- `zfs snapshot`: snapshots are not adopted as volumes, so no change is
  needed. Whether they show the zvol's user property as inherited does not
  matter for this design.
- Restore (`ZfsCommands.restoreSnapshot` uses `zfs clone`) and the clone
  service (`zfs send --embed --large-block | zfs receive -F`, without `-p`):
  the new dataset does not carry the origin's local user properties. So the
  satellite must set the marker of the *new* volume right after
  clone/receive, before the volume is reported as existing. Until then the
  device must count as "being created by us", not as foreign. (To verify on
  the rack: property inheritance for `zfs clone` and `zfs receive` without
  `-p`.)
- Backup shipping (`zfs send` of snapshots to another cluster): the receiving
  satellite creates the target volume and must set its own marker. A marker
  received from the source cluster must never count as "ours".
- LVM thin snapshots and restores: tags are not copied by `lvcreate -s`
  (to verify). The marker is set on the restored LV explicitly, as for ZFS.

## Questions for the maintainers

1. Is a per-device ownership marker acceptable in principle, or is there an
   existing mechanism or plan (e.g. in the DB or the layer data) that should
   be used instead?
2. Marker value: volume UUID (detects earlier incarnations) vs.
   node/resource name (stable across recreate)? Any concern with UUID churn
   on, e.g., snapshot restore into a new resource?
3. Default on a foreign or unmarked device: refuse, or rename out of the way
   and create? Should it be a property per storage pool?
4. Migration: opt-in property with `mark`/`enforce` levels, or
   mark-on-first-adjust? How should storage-only resources be handled?
5. Which providers first? We would start with ZFS and LVM thin, which are
   where the thin skip-sync and the #545 leftovers occur.
6. Delete of an unmarked device: forget (proposal) or keep destroying until
   `enforce`? Forgetting can leak space on clusters that never migrate.
7. Interaction with `fix/linstor-server-11` / `-12` (transient flag): keep
   them as the minimal fix and build the marker on top, or replace them?

## Out of scope

The race-winner case (w) is about the DRBD GI layout (#546, 0024071e4), not
about ownership. A marker would let the satellite refuse the adopted device
before it becomes the winner, but the GI question stays open on its own.

---

Prepared with AI assistance at Webdock; reviewed by <engineer>

Title: 9.3.4: resync never concludes after a refused second handshake ("Becoming WFBitMapS because primary is diskless" → -8, postponed); 0-bit SyncTarget stays Inconsistent and invalidate cannot recover it

## Environment

- DRBD kernel module 9.3.4 (api:2/proto:118-125), GIT-hash e59b287b199f7224468af85b68a10e83c68213f5
- drbd-utils 9.35.0
- Kernel 7.2.11-zabbly+, Ubuntu 24.04
- LINSTOR 1.35.2 generates the configuration. `quorum majority`, `c-min-rate 0` in this resource group (also tried
  with 256000, see below), two network paths per connection.

## Topology

| Node | node-id | Role |
|---|---|---|
| B | 0 | diskful, rebooting (SyncTarget) |
| A | 1 | diskful, UpToDate (SyncSource) |
| C | 2 | diskless, **Primary**, writing |
| D | 3 | diskless, Secondary |

Resources: R1 (vol0 10 GiB, vol1 0.49 GiB), R2 (vol0 40 GiB, vol1 0.49 GiB).

## What happens

B was rebooted for firmware updates four times on 2026-10-30. The reconnects described here are at 09:39, 09:53,
10:09 and 10:33 CET.
Each time A began a small bitmap resync as SyncSource toward B, a few hundred KB (vol1: 0 KB). Within 50-100 ms a
second handshake arrived ("Becoming WFBitMapS because primary is diskless"). It was refused with -8 and postponed
"until current resync finished", and from then on the running resync made no progress:

- A: both volumes `replication:SyncSource peer-disk:Inconsistent`, `out-of-sync:0`, `rs-in-flight:0`,
  `resync-suspended:no`, until the next disconnect (the 10:09 occurrence was still stuck at 10:20).
- B: R2 vol0 SyncTarget 81.25 % done, out-of-sync 12 KB, rs-in-flight 0, no resync requests issued. In another
  stalled round the residue on B grew from 52 to 64 KiB with the Primary's writes. A reported out-of-sync 0 toward B
  at the same time as B reported a non-zero residue. R1 out-of-sync 0 but still SyncTarget. vol1 of both resources began
  with "will sync 0 KB [0 bits set]" and stayed SyncTarget/Inconsistent; "Resync done" never came (boots lasted
  up to 16 min).
- No "Deferring resync request" and no "divergence bitmap not cleared yet" on either side.
- The same two resources hung on every boot; about 92 other resources on the same pair of nodes finished in
  1-2 s.

Logged on A (peer name `B` in the prefix), CET, resource name shortened:

```
2026-10-30T10:09:22.156258+01:00 drbd R1/0 drbd1127 B: uuid_compare()=source-use-bitmap by rule=bitmap-self
2026-10-30T10:09:22.168273+01:00 drbd R1/1 drbd1128 B: uuid_compare()=source-use-bitmap by rule=bitmap-self
2026-10-30T10:09:22.176231+01:00 drbd R1/0 drbd1127 B: repl( Off -> WFBitMapS ) [remote]
2026-10-30T10:09:22.176275+01:00 drbd R1/1 drbd1128 B: repl( Off -> WFBitMapS ) replication( yes -> no ) [remote]
2026-10-30T10:09:22.190716+01:00 drbd R1/0 drbd1127 B: repl( WFBitMapS -> SyncSource ) [receive-bitmap]
2026-10-30T10:09:22.190788+01:00 drbd R1/0 drbd1127 B: Began resync as SyncSource (will sync 192 KB [48 bits set]).
2026-10-30T10:09:22.207002+01:00 drbd R1/1 drbd1128 B: repl( WFBitMapS -> SyncSource ) [receive-bitmap]
2026-10-30T10:09:22.208073+01:00 drbd R1/1 drbd1128 B: Began resync as SyncSource (will sync 0 KB [0 bits set]).
2026-10-30T10:09:22.251581+01:00 drbd R1/0 drbd1127 B: drbd_sync_handshake:
2026-10-30T10:09:22.251681+01:00 drbd R1/0 drbd1127 B: uuid_compare()=source-use-bitmap by rule=bitmap-self
2026-10-30T10:09:22.251694+01:00 drbd R1/0 drbd1127 B: Becoming WFBitMapS because primary is diskless
2026-10-30T10:09:22.251731+01:00 drbd R1: State change failed: Can not start OV/resync since it is already active (-8)
```

followed on A by `Failed: repl( SyncSource -> WFBitMapS ) [diskless-primary]` and "...postponing this until current
resync finished" (quoted from our notes; the exact lines with timestamps can be provided).

Logged on B (peer name `A` in the prefix), at the 10:33 CET boot, times in UTC as logged:

```
2026-10-30T09:33:42.663855+00:00 drbd R1/0 drbd1127 A: Began resync as SyncTarget (will sync 344 KB [86 bits set]).
2026-10-30T09:33:42.677873+00:00 drbd R1/1 drbd1128 A: Began resync as SyncTarget (will sync 0 KB [0 bits set]).
2026-10-30T09:33:52.025876+00:00 drbd R2/0 drbd1123 A: Began resync as SyncTarget (will sync 628 KB [157 bits set]).
2026-10-30T09:33:52.251852+00:00 drbd R2/1 drbd1124 A: Began resync as SyncTarget (will sync 0 KB [0 bits set]).
```

## Recovery attempts that did not work

1. **Rate:** `c-min-rate 256000` on either side for 60 s changed nothing. A disconnect/connect from A (R1,
   10:44:18 CET) reconnected and began a 132 KB resync, which stalled again at 89.19 % with out-of-sync 16 KB and
   rs-in-flight 0.
2. **invalidate while disconnected from the only diskful peer:** on B, `drbdsetup disconnect <res> 1`
   (10:50:36 CET, rc 0), then `drbdadm invalidate <res>` (10:50:43 CET, rc 0). Logged on B (UTC): the only state
   changes are `0->3`, i.e. from B (node-id 0) toward the **diskless** D (node-id 3), and no bits are set:
   ```
   2026-10-30T09:50:43.559854+00:00 drbd R2: Preparing cluster-wide state change 1743608095: 0->3 repl( StartingSyncT )
   2026-10-30T09:50:43.561853+00:00 drbd R2: Preparing cluster-wide state change 3702830413: 0->3 repl( StartingSyncT )
   ```
   On reconnect (logged on B) the handshake carried `bits:0` and the resync again began with 0 bits:
   ```
   2026-10-30T09:51:16.946038+00:00 drbd R2/0 drbd1123 A: peer 5E261F634751CA62:B868F82AD1C6FAC6:8B94AA4977C17F88:D3E449FA5A1B6350 bits:0 flags:1100
   2026-10-30T09:51:16.946081+00:00 drbd R2/0 drbd1123 A: uuid_compare()=target-use-bitmap by rule=bitmap-peer
   2026-10-30T09:51:16.972044+00:00 drbd R2/0 drbd1123 A: Began resync as SyncTarget (will sync 0 KB [0 bits set]).
   2026-10-30T09:51:16.990232+00:00 drbd R2/1 drbd1124 A: Began resync as SyncTarget (will sync 0 KB [0 bits set]).
   ```
   An `invalidate` while connected and stuck (10:49:50 CET) also returned rc 0 with only the same two
   `StartingSyncT` state changes, a silent no-op.
3. **invalidate while connected, with the source named:** `drbdadm invalidate --sync-from-peer-node-id=1 <res>`
   (10:51:53 CET) on B returns rc 11 (logged on B, UTC):
   ```
   2026-10-30T09:51:53.879378+00:00 drbd R2: State change failed: Can not start OV/resync since it is already active (-8)
   2026-10-30T09:51:53.879476+00:00 drbd R2/0 drbd1123 A: Failed: repl( SyncTarget -> StartingSyncT ) [invalidate]
   ```
   The stuck 0-bit resync counts as "active".

The only thing that worked was deleting B's replica and creating it again (LINSTOR `resource delete` / `resource
create`): the new replica connects as just-created and takes a full sync (R2: 40 GiB in about 90 s; R1: 10 GiB in
16 s). That is a full resync of the whole device for a 12 KB residue.

## Earlier occurrence (2026-10-28, same versions)

A failover test: diskless Primary, two diskful nodes; this time the volume that hung was on the diskful node that
was SyncTarget. vol0 began a 4 KB resync; while it ran the Primary rejoined with a new current UUID. The node
logged "not adopting new current UUID … on Inconsistent disk" / "postponing this until current resync finished",
then "Resync done" with `SyncTarget -> Established [resync-finished]`, then
`unexpected repl_state (Established) in receive_bitmap`. vol0 stayed `disk:Inconsistent` with no resync running
and out-of-sync 0, and never adopted the Primary's UUID. A manual disconnect/connect 7 minutes later found 19
bits (76 KB) and resynced them in 1 s. That `unexpected repl_state` line is the one in #148, which has a different
trigger (the sync source losing the diskless Primary).

## What we do not have yet

B's kernel lines at the moment of the refused second handshake (10:09:22 CET), and `drbdsetup status --verbose` /
`drbdsetup events2` captures from both sides while a resync is parked. The A and B status values above are from
`drbdsetup status` taken during the stalls. We can capture events2 on both sides and the `drbdsetup show` of R1/R2
(two paths per connection) on the next occurrence, or provide anything else you need.

## Questions

1. Should the refused and postponed second handshake ("Becoming WFBitMapS because primary is diskless", -8) park
   the running resync like this, with no progress and no requests from the SyncTarget?
2. Why does a SyncTarget that starts with 0 bits not conclude at once?
3. Should `invalidate` (a) pick a diskless peer as its sync source, and (b) refuse with -8 when the "active" resync
   has nothing left to do?
4. What in-band recovery do you recommend instead of re-creating the replica?

Related: #148 (resync hangs after "unsecured-resync"; same `unexpected repl_state (Established) in receive_bitmap`),
#149 (multi-source resync stalls, source and target disagree on out-of-sync bits).

---

Prepared with AI assistance at Webdock

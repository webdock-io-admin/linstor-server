Title: 9.3.4: after a conflicting write in the allow-two-primaries window of a live migration: "FATAL: Local commit of prepared … failed!", two new current UUIDs, and a SyncTarget that stays Inconsistent while one of its legs is StandAlone

## Environment

- DRBD kernel module 9.3.4 (api:2/proto:118-125), GIT-hash e59b287b199f7224468af85b68a10e83c68213f5, drbd-dkms 9.3.4-1ppa1~noble1 on S, T and B. A runs 9.3.4 + b4fa5b19 (the fix for the spurious NetworkFailure after a handshake, see #160). drbd-utils 9.35.0-1ppa1~noble1.
- Kernel 7.2.11-zabbly+, Ubuntu 24.04. LINSTOR 1.35.2 generates the configuration. `allow-two-primaries yes` is set for the duration of a VM live migration. `quorum majority`.
- Nodes: S, the migration source, and T, the migration target, are both diskless. A and B are diskful storage nodes. Before the move, S was Primary with the VM running; T promoted for the migration. There is no S-T connection: the diskless nodes do not connect to each other.

## What happened (times CET)

First, what caused it, and that it is on our side: the hypervisor's source host wrote 64 KiB to a small per-instance volume (the VM's firmware variables store) after the target had promoted and was already using that volume. So the two Primaries wrote the same blocks. We know that dual-primary requires the users of the device to avoid overlapping writes, and we are fixing that on our side. We are reporting how DRBD came out of it.

1. 05:54:57.69: T promotes while S stays Primary.
2. 05:54:59.70: S writes the overlapping 64 KiB. DRBD logs `Concurrent writes detected: local=…, remote=…` (`drbd_peer_write_conflicts()`, drbd_receiver.c:3598-3616, -EBUSY), then `error receiving P_DATA, e: -16`, then `ProtocolError`.
3. Both Primaries generate a new current UUID, giving two generations.
4. Within the next 0.2 s, A and B log five `Refusing to be Outdated while Connected (-6)` between them (`SS_CONNECTED_OUTDATES`, drbd_state.c:2297) and three FATAL commit failures: twice `FATAL: Local commit of prepared <tid> failed!` (drbd_receiver.c:9319) and once `FATAL: Local commit of already committed <tid> failed!` (drbd_state.c:6226). The same tid 2194040359 appears as "already committed" on A and as "prepared" on B.
5. The reconnects are refused in the cluster-wide state change (`Declined by peer …`, drbd_state.c:5614). A's handshake with S shows different exposed UUIDs (vol0 `5D2C35F4…` vs `53043FF1…`). See also #157 for exposed-UUID handling on reconnect.
6. Exactly two connections ended StandAlone, both ways: S-A and T-B. S-B, T-A and A-B stayed Connected. When S demoted at 05:56:00.36, B's disk went from UpToDate to Outdated.

Key lines per node (CET; R = the resource; S, T, A, B as above):

```
A  05:54:59.696 R/1 drbd1160: Concurrent writes detected: local=267904s +65536, remote=267904s +65536
A  05:54:59.728 R S: error receiving P_DATA, e: -16 l: 65536!
A  05:54:59.730 R S: conn( Connected -> ProtocolError ) peer( Primary -> Unknown )
A  05:54:59.747 R: State change failed: Refusing to be Outdated while Connected (-6)
A  05:54:59.768 R: FATAL: Local commit of already committed 2194040359 failed!
A  05:54:59.768 R S: Restarting receiver thread
A  05:54:59.899 R: State change failed: Refusing to be Outdated while Connected (-6)
A  05:54:59.909 R: FATAL: Local commit of prepared 3770116309 failed!
A  05:55:00.29  R S: handshake, exposed UUID differs (vol0 5D2C35F4… vs 53043FF1…); Declined by peer S
A  05:55:00.431 R S: StandAlone

B  05:54:59.693 R/1 drbd1160: Concurrent writes detected: local=267904s +65536, remote=267904s +65536
B  05:54:59.725 R T: error receiving P_DATA, e: -16 l: 65536!
B  05:54:59.727 R T: conn( Connected -> ProtocolError ) peer( Primary -> Unknown )
B  05:54:59.764 R T: Restarting receiver thread
B  05:54:59.787 / .821 / .859 R: State change failed: Refusing to be Outdated while Connected (-6)
B  05:54:59.800 R: FATAL: Local commit of prepared 2194040359 failed!
B  05:55:00.30  R T: reconnect: Declined by peer A; StandAlone
B  05:56:00.36  R: disk( UpToDate -> Outdated ) after S demoted

S  05:54:59.735 R A: conn( Connected -> BrokenPipe )
S  05:54:59.758 R A: Restarting receiver thread
S  05:54:59.81  R A: peer disks DUnknown -> Outdated [far-away]
S  05:54:59.958 R: Declined by peer B (id: 1); then StandAlone toward A
S  05:56:00.36  R: role( Primary -> Secondary )

T  05:54:56.9   R: connect
T  05:54:57.69  R: role( Secondary -> Primary ) (auto-promote; S and T now both Primary)
T  05:54:59.75  R B: Restarting receiver thread
T  05:54:59.85  R: Declined by peer A (id: 0)
T  05:55:00.308 R B: disconnect from B; T-B StandAlone
```

The hypervisor could not stop the VM on S cleanly (the diskless resource was still in use) and stopped it by force; S demoted at 05:56:00.36.

## Repair: a SyncTarget that stays Inconsistent

After the move, T was the only Primary, with the VM running, and A the only UpToDate node. B was the SyncTarget from A, while B's own connection to T was StandAlone (step 6). T kept writing through A.

- The handshake toward the UpToDate peer stays at `Ignore Split-Brain, for now, at least one side unstable` (drbd_receiver.c:6729-6732, strategy forced to `NO_SYNC`). So `drbdadm connect --discard-my-data` has no effect.
- A full `drbdadm invalidate` resync completes with `Peer was unstable during resync` (drbd_sender.c:2061). B stays `Inconsistent` indefinitely, and the resync-finished UUID is not adopted.
- It resolved only after the T-B connection came up and a second `invalidate` ran.

See also #158, a different trigger with the same end state: a SyncTarget that stays `Inconsistent`, and `invalidate` cannot recover it.

## Questions

1. Are the `FATAL: Local commit of prepared/already committed … failed!` messages expected after a ProtocolError on concurrent writes between two Primaries, or do they point to a state-machine problem? Note that one tid (2194040359) is "already committed" on A and "prepared" on B.
2. Is there a recommended `after-sb-*` setting, or a procedure, for the short dual-primary window of a live migration, so that a conflicting write ends in a defined state instead of two generations?
3. Repair: is it intended that a SyncTarget whose peer is "unstable" (the Primary writes through it while the SyncTarget's own connection to the Primary is down) never leaves `Inconsistent` after a completed resync? If so, is the required order (bring up the connection to the Primary first, then invalidate) documented?

---

Prepared with AI assistance at Webdock

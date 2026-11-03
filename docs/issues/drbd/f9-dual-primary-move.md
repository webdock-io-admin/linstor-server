Title: 9.3.4: after a conflicting write in the allow-two-primaries window of a live migration: "FATAL: Local commit of prepared … failed!", two new current UUIDs, and a SyncTarget that stays Inconsistent while one of its legs is StandAlone

## Environment

- DRBD kernel module 9.3.4 (api:2/proto:118-125), GIT-hash e59b287b199f7224468af85b68a10e83c68213f5, drbd-dkms 9.3.4-1ppa1~noble1, plus b4fa5b19 on <which nodes>. drbd-utils 9.35.0-1ppa1~noble1.
- Kernel 7.2.11-zabbly+, Ubuntu 24.04. LINSTOR 1.35.2 generates the configuration. `allow-two-primaries yes` is set for the duration of a VM live migration. `quorum majority`.
- Nodes: source S (Primary, VM running), target T (promoted for the migration), and storage nodes A and B (diskful). <role mapping to confirm>

## What happened (times CET)

First, what caused it, and that it is on our side: the hypervisor's source host wrote 64 KiB to a small per-instance volume (the VM's firmware variables store) after the target had promoted and was already using that volume. So the two Primaries wrote the same blocks. We know that dual-primary requires the users of the device to avoid overlapping writes, and we are fixing that on our side. We are reporting how DRBD came out of it.

1. 05:54:57.69: T promotes while S stays Primary.
2. 05:54:59.70: S writes the overlapping 64 KiB. DRBD logs `Concurrent writes detected: local=…, remote=…` (`drbd_peer_write_conflicts()`, drbd_receiver.c:3598-3616, -EBUSY), then `error receiving P_DATA, e: -16`, then `ProtocolError`.
3. Both Primaries generate a new current UUID, giving two generations.
4. Afterwards the log shows five `Refusing to be Outdated while Connected (-6)` (`SS_CONNECTED_OUTDATES`, drbd_state.c:2297) and three `FATAL: Local commit of prepared <tid> failed!` (drbd_receiver.c:9319).
5. The reconnect is `Declined by peer` (drbd_state.c:5614) because of the diverged exposed UUID. See also #157 for exposed-UUID handling on reconnect.
6. Each of the four nodes lost a different connection.

<excerpts per node, from the evidence once pushed>

## Repair: a SyncTarget that stays Inconsistent

During the repair, one node X was SyncTarget from the only UpToDate peer, while its own connection to the Primary was StandAlone and the Primary kept writing through that UpToDate peer.

- The handshake toward the UpToDate peer stays at `Ignore Split-Brain, for now, at least one side unstable` (drbd_receiver.c:6729-6732, strategy forced to `NO_SYNC`). So `drbdadm connect --discard-my-data` has no effect.
- A full `drbdadm invalidate` resync completes with `Peer was unstable during resync` (drbd_sender.c:2061). X stays `Inconsistent` indefinitely, and the resync-finished UUID is not adopted.
- It resolved only after the connection between X and the Primary came up and a second `invalidate` ran.

## Questions

1. Is `FATAL: Local commit of prepared … failed!` expected after a ProtocolError on concurrent writes between two Primaries, or does it point to a state-machine problem?
2. Is there a recommended `after-sb-*` setting, or a procedure, for the short dual-primary window of a live migration, so that a conflicting write ends in a defined state instead of two generations?
3. Repair: is it intended that a SyncTarget whose peer is "unstable" (the Primary writes through it while the SyncTarget's own connection to the Primary is down) never leaves `Inconsistent` after a completed resync? If so, is the required order (bring up the connection to the Primary first, then invalidate) documented?

---

Prepared with AI assistance at Webdock

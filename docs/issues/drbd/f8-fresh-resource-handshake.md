Title: 9.3.4: connect livelock of a fresh resource — several diskless peers handshake at once, each attempt ends in "Interrupted state change (-21)" / "timeout while waiting for feature packet", retrying in lock-step every ~6 s for hours

## Environment

- DRBD kernel module 9.3.4 (api:2/proto:118-125), GIT-hash e59b287b199f7224468af85b68a10e83c68213f5, drbd-dkms 9.3.4-1ppa1~noble1 from ppa:linbit/linbit-drbd9-stack; drbd-utils 9.35.0-1ppa1~noble1
- Kernel 7.2.11-zabbly+, Ubuntu 24.04
- LINSTOR 1.35.2 generates the configuration. Resources with 6 nodes: 2 diskful (storage nodes A, B) and 4 diskless (compute nodes). `quorum majority`.
- Transport `tcp` (not lb-tcp), `load-balance-paths no`, tls no. Two paths per connection, one per network fabric. Net options: `connect-int 3`, `ping-int 3` (our tuning); `ping-timeout 5`, `timeout 60`, `ko-count 7` (defaults); protocol C.
- Load: about 140 new resources (LINSTOR `resource-definition clone`) from four parallel create streams on 2026-11-02 morning.

## What happens

Two of the ~140 resources, created 12.6 s apart (07:07:36.8 and 07:07:49.4 CET), never connected to their diskless peers. On the storage pair both came up UpToDate. Toward every diskless peer, the connection attempt fails and repeats about every 6 s, still looping 3 hours later (85 of 321 kernel lines in 2 min on one diskless node).

The three diskless peers of each resource started their handshakes within the same seconds. Resources whose diskless peers arrived one at a time connected normally (one of them after a single -21).

Node ids: storage nodes A = 0, B = 1; diskless C = 2, D = 3, E = 4, F = 5. F connected normally; C, D and E loop.

Diskful side (B), toward C (node-id 2), first cycle (CET):

```
07:07:39.393 conn( Unconnected -> Connecting ) [connecting]
07:07:57.951 Handshake to peer 2 successful: Agreed network protocol version 125
07:07:57.951 Feature flags enabled on protocol level: 0x7ff TRIM THIN_RESYNC WRITE_SAME WRITE_ZEROES RESYNC_DAGTAG WRITE_POSTPONE
07:07:57.951 Peer authenticated using 20 bytes HMAC
07:07:57.951 Preparing cluster-wide state change 1935212143: 1->2 role( Secondary ) conn( Connected )
07:07:57.962 meta connection shut down by peer.
07:07:57.962 conn( Connecting -> NetworkFailure )
07:07:57.963 sock was shut down by peer
07:07:57.964 Aborting cluster-wide state change 1935212143 (12ms) rv = -21
07:07:57.964 Failure to connect: Interrupted state change (-21); retrying
07:07:57.976 Connection closed; helper command: /sbin/drbdadm disconnected (exit 0)
07:07:58.074 conn( NetworkFailure -> Unconnected ) [disconnected]; Restarting receiver thread; conn( Unconnected -> Connecting )
```

The same pattern toward E (node-id 4) at 07:08:09.983-07:08:10.098 (abort after 6 ms) and toward D at 07:08:55.

Diskless side (D, node-id 3), toward A (node-id 0), first cycle; identical toward B (CET):

```
07:07:36.680 Starting worker thread (node-id 3)
07:07:36.723 susp-io( no -> quorum ) [connect]
07:07:36.723 conn( StandAlone -> Unconnected ) [connect]; Starting receiver thread (peer-node-id 0)
07:07:36.724 conn( Unconnected -> Connecting ) [connecting]
07:07:47.134 sock_recvmsg returned -11
07:07:47.134 conn( Connecting -> BrokenPipe )
07:07:47.134 short read (expected size 8)
07:07:47.134 timeout while waiting for feature packet
07:07:47.134 Terminating sender thread
07:07:47.135 Starting sender thread (peer-node-id 0)
```

This repeats every ~6-10 s: 28 cycles in the first 2 minutes, still looping at 10:45. Timing at creation: the diskless resource on D came up at 07:07:36.68, before the diskful resource on B started its threads (07:07:39.09); A and B were UpToDate at 07:07:39.

Side by side for one pair, B (diskful, node-id 1) and D (diskless, node-id 3), a later cycle (CET):

```
D 07:08:41.149  sock_recvmsg returned -11; conn( Connecting -> BrokenPipe ); short read (expected size 8); timeout while waiting for feature packet
D 07:08:42.292  conn( Unconnected -> Connecting )
D 07:08:48.318  (same timeout, 6.03 s after connecting)
D 07:08:49.460  conn( Unconnected -> Connecting )
B 07:08:55.103  Handshake to peer 3 successful: Agreed network protocol version 125; Feature flags …; Peer authenticated using 20 bytes HMAC
D 07:08:55.104  Handshake to peer 1 successful: Agreed network protocol version 125; Feature flags …; Peer authenticated using 20 bytes HMAC
B 07:08:55.103  Preparing cluster-wide state change 7832961: 1->3 role( Secondary ) conn( Connected )
D 07:08:55.104  conn( Connecting -> NetworkFailure ) [disconnected]
B 07:08:55.110  sock was shut down by peer; conn( Connecting -> BrokenPipe ); meta connection shut down by peer (x2)
B 07:08:55.111  Aborting cluster-wide state change 7832961 (7ms) rv = -21; Failure to connect: Interrupted state change (-21); retrying
D 07:08:55.188  conn( Unconnected -> Connecting )
B 07:08:55.193  conn( Unconnected -> Connecting )
D 07:09:01.236  timeout while waiting for feature packet (6.05 s later)
D 07:09:02.388  conn( Unconnected -> Connecting )
D 07:09:08.404  timeout while waiting for feature packet
```

The handshake completes on both sides at the very end of D's wait (55.103 / 55.104). In the same millisecond, D's timeout path wins over its handshake-success path (`NetworkFailure [disconnected]`), and B sees the peer closing (-21). Every retry is "connect + 6.0 s + ~1.1 s" on both sides, so the phase never drifts. The 6.0 s matches the net `timeout` (default 60, i.e. 6.0 s); the first cycle after creation waited 10.4 s.

On the wire: the TCP connect to the storage node's listener succeeds, but no feature packet arrives. The diskless side closes after its timeout (FIN-WAIT-2 seen), and at that moment the diskful side logs the -21.

The excerpts above show the pattern, not proof of the exact cause. Not a port mix-up: LINSTOR assigns a port per resource per node. A scan of all 4,369 (node, port) pairs found no duplicates, the failing resources are the only owners of their ports on every node, and the kernel resource set on each diskless node equals LINSTOR's.

## Experiment (on one of the stuck resources)

<results pending: (1) disconnect two of the three diskless peers, check whether the remaining one connects, then reconnect the others one at a time; (2) remove one path (keep s0) on both sides; (3) `connect-int` back to 10 s>

## Our reading

A connect livelock, phase-locked across cycles. The feature exchange takes the whole net `timeout` window, the handshake completes on both sides at its very end, and the diskless side's timeout fires in the same millisecond and wins. The diskful side then aborts its state change to Connected with -21, and both sides reconnect with identical timing, every cycle, for hours. It happened only where several diskless peers connected to a fresh resource in the same seconds. Each connection has two paths, and the tcp transport tries both at connect time. Our guess: the handshake completes on one path while the attempt on the other path waits for its feature packet; when that wait hits `timeout` (6 s), it tears down the connection the first path has just completed, in lock-step every cycle.

## Consequence

The resource is UpToDate on the storage pair but unusable on the compute nodes. In our setup Incus's open fails ("no data available") and its create retry leaves an orphaned resource definition.

## Possibly related

- 82e8fa8de "drbd: lb-tcp: drop the listeners of the paths that lost the connect race" and 919b15f86 "drbd: lb-tcp: hand a control socket back before closing it" (2026-09-24, drbd-9.2 branch, merged into master as 2a205bc1c), not in 9.3.4. We use the plain `tcp` transport, so these do not apply as such, but they fix a similar race between the paths of one connection at connect time.
- 2a9f6429d "drbd: Fix a distributed loop while establishing the connection" (2018), which quotes the same "short read" / feature-packet lines; already in 9.3.4.

## Questions

1. Why does the feature exchange take the whole net `timeout`? With two paths, can the attempt on the losing path tear down the connection the other path has just established?
2. When the handshake succeeds in the same millisecond as the feature-packet timeout fires, should the success win? And should the reconnect cycle have jitter, so that such a phase lock breaks by itself?
3. Is there a configuration setting (e.g. a larger `timeout` or `connect-int`) that avoids this for a fresh resource with several diskless peers, or a recommended in-band recovery?

Our mitigation: none yet. <pending the experiments above>

---

Prepared with AI assistance at Webdock

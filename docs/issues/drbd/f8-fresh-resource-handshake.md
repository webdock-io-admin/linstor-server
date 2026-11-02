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

In this cycle the feature exchange and authentication succeed on both sides (55.103 / 55.104). D logs "Handshake … successful" only after it has received B's feature packet (drbd_receiver.c:12177). In the same millisecond, D runs `conn_disconnect()`, which logs `NetworkFailure [disconnected]` (:11869). B, which as the lower node-id has just started the cluster-wide state change to Connected, sees the meta connection shut down and aborts with -21. D's log does not say why it disconnected right after a successful handshake. Its complete kernel log for the resource between 55.104 and 55.188 is only the teardown (sender thread restart, `Connection closed`, `disconnected` helper exit 0, `Unconnected -> Connecting`). D's other connection of the same resource, toward A (node-id 0), had gone `Unconnected -> Connecting` 0.14 s earlier (07:08:54.964). D's two connections cycle in near lock-step. In the other cycles, D's feature-packet wait times out. Every retry is "Connecting + 6.0 s, then ~1.1 s to the next Connecting" on both sides, so the phase never drifts. The first cycle after creation waited 10.4 s.

On the wire: the TCP connect to the storage node's listener succeeds, but no feature packet arrives. The diskless side closes after its timeout (FIN-WAIT-2 seen), and at that moment the diskful side logs the -21.

The excerpts above show the pattern, not proof of the exact cause. Not a port mix-up: LINSTOR assigns a port per resource per node. A scan of all 4,369 (node, port) pairs found no duplicates, the failing resources are the only owners of their ports on every node, and the kernel resource set on each diskless node equals LINSTOR's.

## Experiment (on one of the stuck resources)

<results pending: (1) disconnect two of the three diskless peers, check whether the remaining one connects, then reconnect the others one at a time; (2) remove one path (keep s0) on both sides; (3) `connect-int` back to 10 s>

## What the 9.3.4 source says about the timing (tag drbd-9.3.4, e59b287b1)

- After a failed feature exchange, `conn_connect()` goes to `retry:`, calls `conn_disconnect()` and then sleeps a fixed `schedule_timeout_interruptible(HZ)`, 1 s with no jitter (drbd/drbd_receiver.c:1348-1353). That is the ~1.1 s gap we see (about 80 ms of it is the `disconnected` helper).
- The feature-packet wait is the data socket's receive timeout, set to `ping_timeo * 4 * HZ/10` just before `drbd_do_features()` (drbd_receiver.c:1265), i.e. 2.0 s with the default `ping-timeout 5`. The net `timeout` option is used only as the send timeout and the TLS hello timeout in `dtt_connect()` (drbd/drbd_transport_tcp.c:1337, :1422-1423). So the 6.0 s is not the `timeout` option. It is roughly 4 s inside `dtt_connect()` plus the 2.0 s feature wait.
- Inside `dtt_connect()`, each `dtt_connection_established()` check sleeps `sock_check_timeo` or `ping_timeo` (0.5 s) (drbd_transport_tcp.c:672-674). `dtt_wait_for_connect()` waits `connect_int` with ±1/7 jitter, i.e. 2.57 or 3.43 s with `connect-int 3` (:759-760). Outgoing sockets use `connect_int` as their send/receive timeout (:504-505), and the first-packet receive uses `ping_timeo * 4` (:872). We have not worked out which combination of these makes the ~4 s. The steady 6.0 s across cycles suggests the same branch is taken every time.
- Each side sends its feature packet first and then waits for the peer's (`drbd_do_features()`, drbd_receiver.c:12065-12082). A 2 s wait on a working socket pair therefore means that, at that moment, the two sides were not holding the same data socket. With two paths, the two sides could each come out of `dtt_connect()` with a different socket pair.

## Our reading

A connect livelock, phase-locked across cycles. Each cycle ends either with the diskless side's feature-packet timeout or, when the handshake does succeed, with the diskless side disconnecting in the same millisecond, before B's state change to Connected can complete. The diskful side then aborts its state change to Connected with -21, and both sides reconnect with identical timing, every cycle, for hours. It happened only where several diskless peers connected to a fresh resource in the same seconds. Each connection has two paths, and the tcp transport tries both at connect time. Our guess: the two sides end up with socket pairs from different paths, or one side's pair goes stale. The diskless side then waits out its 2 s feature timeout on a socket the diskful side is not using, When a cycle does complete the handshake, something tears it down within milliseconds; we cannot see what from the logs. The fixed 1 s retry sleep then puts both sides back in the same phase.

## Consequence

The resource is UpToDate on the storage pair but unusable on the compute nodes. In our setup Incus's open fails ("no data available") and its create retry leaves an orphaned resource definition.

## Possibly related

- 82e8fa8de "drbd: lb-tcp: drop the listeners of the paths that lost the connect race" and 919b15f86 "drbd: lb-tcp: hand a control socket back before closing it" (2026-09-24, drbd-9.2 branch, merged into master as 2a205bc1c), not in 9.3.4. We use the plain `tcp` transport, so these do not apply as such, but they fix a similar race between the paths of one connection at connect time.
- 2a9f6429d "drbd: Fix a distributed loop while establishing the connection" (2018), which quotes the same "short read" / feature-packet lines; already in 9.3.4.

## Questions

1. With the tcp transport and two paths, can `dtt_connect()` return on each side with a socket pair from a different path (or one the peer has already dropped), so that the feature exchange can only time out?
2. `dtt_wait_for_connect()` has jitter, but the 1 s sleep after a failed handshake in `conn_connect()` (drbd_receiver.c:1352) does not. Would jitter there (or a growing back-off) be acceptable, so that a phase lock like this breaks by itself?
3. In the cycle where the handshake succeeds on both sides, what makes the diskless side call `conn_disconnect()` in the same millisecond, before the state change to Connected? Can the connect attempt of D's other connection of the same resource (toward A, restarted 0.14 s earlier) interfere with this one? Is there a log level that would show it?
4. Is there a configuration setting (e.g. a larger `ping-timeout`, `sock-check-timeo` or `connect-int`) that avoids this for a fresh resource with several diskless peers, or a recommended in-band recovery?

Our mitigation: none yet. <pending the experiments above>

---

Prepared with AI assistance at Webdock

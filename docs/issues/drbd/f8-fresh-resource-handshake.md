Title: 9.3.4: connect livelock of a fresh resource — diskless peers never connect: feature-packet timeouts alternate with successful handshakes torn down at once (the bug b4fa5b19 fixes), in lock-step for hours

## Environment

- DRBD kernel module 9.3.4 (api:2/proto:118-125), GIT-hash e59b287b199f7224468af85b68a10e83c68213f5, drbd-dkms 9.3.4-1ppa1~noble1 from ppa:linbit/linbit-drbd9-stack; drbd-utils 9.35.0-1ppa1~noble1
- Kernel 7.2.11-zabbly+, Ubuntu 24.04
- LINSTOR 1.35.2 generates the configuration. Resources with 6 nodes: 2 diskful (storage nodes A, B) and 4 diskless (compute nodes). `quorum majority`.
- Transport `tcp` (not lb-tcp), `load-balance-paths no`, tls no. Two paths per connection, one per network fabric. Net options: `connect-int 3`, `ping-int 3` (our tuning); `ping-timeout 5`, `timeout 60`, `ko-count 7` (defaults); protocol C.
- Load: about 140 new resources (LINSTOR `resource-definition clone`) from four parallel create streams on 2026-11-02 morning.

## What happens

Two of the ~140 resources, created 12.6 s apart (07:07:36.8 and 07:07:49.4 CET), never connected to their diskless peers. On the storage pair both came up UpToDate. Toward every diskless peer, the connection attempt fails and repeats about every 6 s, still looping 3 hours later (85 of 321 kernel lines in 2 min on one diskless node).

The three diskless peers of each resource started their handshakes within the same seconds. Resources whose diskless peers arrived one at a time connected normally (one of them after a single -21). The experiment below shows that the simultaneous arrival is not needed to keep the loop going.

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

In this cycle the feature exchange and authentication succeed on both sides (55.103 / 55.104). D logs "Handshake … successful" only after it has received B's feature packet (drbd_receiver.c:12177). In the same millisecond, D runs `conn_disconnect()`, which logs `NetworkFailure [disconnected]` (:11869). B, which as the lower node-id has just started the cluster-wide state change to Connected, sees the meta connection shut down and aborts with -21. D logs no reason. Its complete kernel log for the resource between 55.104 and 55.188 is only the teardown: sender thread restart, `Connection closed`, `disconnected` helper exit 0, receiver thread restart, `Unconnected -> Connecting`. The cause is explained under "Matches an upstream fix" below. In the other cycles, D's feature-packet wait times out, and the next `Connecting` follows about 1.1 s later. After a torn-down handshake it follows 0.08-0.2 s later. D's two connections (toward A and B) cycle in near lock-step, so the phase does not drift. The first cycle after creation waited 10.4 s.

On the wire: the TCP connect to the storage node's listener succeeds, but no feature packet arrives. The diskless side closes after its timeout (FIN-WAIT-2 seen), and at that moment the diskful side logs the -21.

The excerpts above show the pattern, not proof of the exact cause. Not a port mix-up: LINSTOR assigns a port per resource per node. A scan of all 4,369 (node, port) pairs found no duplicates, the failing resources are the only owners of their ports on every node, and the kernel resource set on each diskless node equals LINSTOR's.

## Experiment (on one of the stuck resources)

1. Single diskless peer (10:20-10:23 CET): C and E were disconnected to StandAlone, on both diskful nodes at the same step. D alone still did not connect within 60 s. Reconnecting C and then E changed nothing. An earlier pass that disconnected only on the diskful side gave the same result. So the loop does not need several peers connecting at once. Once a connection is in it, the loop belongs to that single connection, or to the resource on the diskless side.
2. One path only (10:25-10:30 CET): the second path was removed on the diskful side (both diskful nodes). The diskless side kept both paths configured, but its attempts on the second path found no listener. No effect: all three diskless peers stayed `Connecting`. Within that minute, B logged three cycles toward C of handshake successful, then -21, on the first path alone. So the loop does not need a race between two paths.
3. `connect-int 10` on the diskful side (both diskful nodes), same window: no effect. In that minute the diskful side saw no handshake at all. The diskless side kept `connect-int 3`.

All changes were restored afterwards. So the peer count, the path count and the retry interval, each changed from the diskful side, do not break the loop.
4. From the diskless side D (10:3x CET):
   a. `drbdsetup disconnect` and `connect` of one connection: still `Connecting` after 60 s.
   b. `drbdsetup down`, then `drbdadm adjust`: the connection to B came up within 5 s (handshake OK, protocol 125, both volumes UpToDate, quorum regained). The connection to A kept looping with the same feature-packet-timeout cycle, although D's side of it had just been recreated.
   c. A second `adjust` on D changed nothing.

   So the stuck state is per connection, and it can sit on the diskful end as well. Recreating D's side cleared the connection to B but not the one to A.
5. Reset of A's side of the A-D connection (disconnect/connect at 10:36:51 CET, adjust at 10:38:07): not recovered. Both windows show the same pattern on D, complete lines for the resource:

   ```
   10:37:31.155928 Handshake to peer 0 successful: Agreed network protocol version 125
   10:37:31.156113 Feature flags enabled on protocol level: 0x7ff …
   10:37:31.156143 Peer authenticated using 20 bytes HMAC
   10:37:31.156169 conn( Connecting -> NetworkFailure ) [disconnected]
   10:37:31.164927 Connection closed; helper disconnected exit 0
   10:37:31.350    conn( Unconnected -> Connecting )
   ```

   (10:38:16.980941-.981250 identical, 31 µs from the HMAC line to `NetworkFailure`.) A logs "handshake successful", then 7 ms later "sock was shut down by peer" and -21. Neither side logs a prepared, rejected or aborted state change from the peer, a UUID line or a role line. Afterwards D-B is Connected/UpToDate (since experiment 4b), D-A is Connecting/DUnknown, and quorum holds.

## What the 9.3.4 source says about the timing (tag drbd-9.3.4, e59b287b1)

- After a failed feature exchange, `conn_connect()` goes to `retry:`, calls `conn_disconnect()` and then sleeps a fixed `schedule_timeout_interruptible(HZ)`, 1 s with no jitter (drbd/drbd_receiver.c:1348-1353). That is the ~1.1 s gap after a feature-packet timeout (about 80 ms of it is the `disconnected` helper).
- The feature-packet wait is the data socket's receive timeout, set to `ping_timeo * 4 * HZ/10` just before `drbd_do_features()` (drbd_receiver.c:1265), i.e. 2.0 s with the default `ping-timeout 5`. The net `timeout` option is used only as the send timeout and the TLS hello timeout in `dtt_connect()` (drbd/drbd_transport_tcp.c:1337, :1422-1423). So the 6.0 s is not the `timeout` option. It is roughly 4 s inside `dtt_connect()` plus the 2.0 s feature wait.
- Inside `dtt_connect()`, each `dtt_connection_established()` check sleeps `sock_check_timeo` or `ping_timeo` (0.5 s) (drbd_transport_tcp.c:672-674). `dtt_wait_for_connect()` waits `connect_int` with ±1/7 jitter, i.e. 2.57 or 3.43 s with `connect-int 3` (:759-760). Outgoing sockets use `connect_int` as their send/receive timeout (:504-505), and the first-packet receive uses `ping_timeo * 4` (:872). We have not worked out which combination of these makes the ~4 s. The steady 6.0 s across cycles suggests the same branch is taken every time.
- Each side sends its feature packet first and then waits for the peer's (`drbd_do_features()`, drbd_receiver.c:12065-12082). A 2 s wait on a working socket pair therefore means that, at that moment, the two sides were not holding the same data socket.

## Matches an upstream fix that is not in 9.3.4

The teardown right after a successful handshake matches b4fa5b19 "drbd: fix spurious NetworkFailure right after a successful handshake" (2026-10-05, on drbd-9.2 and master; no release tag contains it yet). Its description: on a soft handshake failure, `conn_connect()` goes to `retry:`, calls `conn_disconnect()` and loops back to `start:` in the same receiver thread function. `conn_disconnect()` leaves `t_state` at RESTARTING, and only `drbd_thread_setup()` sets it back to RUNNING. The next successful handshake then enters `drbdd()`, whose loop exits at once, and `conn_disconnect()` logs `Connecting -> NetworkFailure` with no error before it.

In 9.3.4: `conn_disconnect()` calls `drbd_thread_restart_nowait()` on the receiver (drbd_receiver.c:12002-12006). `_drbd_thread_stop()` sets RESTARTING and sends no signal when the caller is the thread itself (drbd_main.c:709-719). `drbdd()` loops only `while (get_t_state(&connection->receiver) == RUNNING)` (drbd_receiver.c:11137). `drbd_receiver()` then calls `conn_disconnect()` (:12396-12402), which logs `NetworkFailure [disconnected]` (:11869).

Our logs fit this in every detail:
- 26-31 µs from "Peer authenticated" to `NetworkFailure [disconnected]`, with no log line in between.
- About 0.1-0.2 s, not 1 s, from `NetworkFailure` to the next `Connecting`: the thread restarts ("Restarting receiver thread") instead of taking the 1 s `retry:` sleep.
- Every successful handshake on the diskless side follows feature-packet timeouts inside the same receiver thread run.
- On the peer: no state-change reply at all, just the closed socket and -21.

## Our reading

Two things alternate. (a) Attempts end with "timeout while waiting for feature packet" after 2.0 s and retry in place, and each such retry leaves the receiver at RESTARTING. (b) The next attempt that completes the handshake is then torn down by the bug that b4fa5b19 fixes. The thread restarts, its next attempts time out again, and the cycle repeats. The fixed 1 s sleep keeps both sides in phase. With b4fa5b19, (b) should become a normal connect, so the loop should end at the first successful handshake. What we cannot explain is (a): why, in these connections, the first attempts after each thread restart keep failing in the feature exchange while the peer is reachable. Recreating the diskless side's resource (experiment 4b) cleared one connection, which fits a fresh start on both ends. The A-D connection stays stuck after resets on either end (4b, 5).

## Consequence

The resource is UpToDate on the storage pair but unusable on the compute nodes. In our setup Incus's open fails ("no data available") and its create retry leaves an orphaned resource definition.

## Possibly related

- 82e8fa8de "drbd: lb-tcp: drop the listeners of the paths that lost the connect race" and 919b15f86 "drbd: lb-tcp: hand a control socket back before closing it" (2026-09-24, drbd-9.2 branch, merged into master as 2a205bc1c), not in 9.3.4. We use the plain `tcp` transport, and experiment 2 reproduced the loop on one path, so we list these only for completeness.
- b4fa5b19 (above).
- 2a9f6429d "drbd: Fix a distributed loop while establishing the connection" (2018), which quotes the same "short read" / feature-packet lines; already in 9.3.4.

## Questions

1. Is b4fa5b19 planned for a 9.3.x release (and a 9.2.x one)? It is the part of this loop that turns a successful handshake into a disconnect.
2. Independent of that fix: what can make the feature exchange time out (2.0 s, "short read (expected size 8)") on most attempts of a connection whose peer is reachable and answering on the same path, even right after the receiver thread has restarted? Each side sends its feature packet first (`drbd_do_features()`), so a timeout means that this side's data socket was not the one the peer sent on.
3. The 1 s sleep after a failed handshake in `conn_connect()` (drbd_receiver.c:1352) has no jitter, while `dtt_wait_for_connect()` does. Would jitter or a back-off there be acceptable, so that a phase lock like this one breaks by itself?
4. Until a release with b4fa5b19: is there a recommended in-band recovery for a connection in this state? Disconnect/connect and adjust on either end did not clear it here; `down` and re-adjust on the diskless side cleared one of two.

Recovery so far: `down` and re-adjust of the resource on the diskless node cleared one of its two connections. A reset of the diskful side's end (experiment 5) did not clear the other. Planned mitigation on our side, outside DRBD: when the open of a freshly created diskless resource fails, recreate that diskless resource instead of retrying the open.

---

Prepared with AI assistance at Webdock

Title: 9.3.4: diskless peers of freshly created resources stuck Connecting for hours: feature-packet timeouts, and every successful handshake torn down at once (b4fa5b19 not in a release)

## Environment

- DRBD kernel module 9.3.4 (api:2/proto:118-125), GIT-hash e59b287b199f7224468af85b68a10e83c68213f5, drbd-dkms 9.3.4-1ppa1~noble1 from ppa:linbit/linbit-drbd9-stack; drbd-utils 9.35.0-1ppa1~noble1
- Kernel 7.2.11-zabbly+, Ubuntu 24.04
- LINSTOR 1.35.2 generates the configuration. Each resource has 6 nodes: 2 diskful (A = node-id 0, B = 1) and 4 diskless (C-F = 2-5). `quorum majority`.
- Transport `tcp`, two paths per connection, tls no. `connect-int 3`, `ping-int 3` (ours); `ping-timeout 5`, `timeout 60`, `ko-count 7` (defaults).
- Load: about 140 new resources (LINSTOR `resource-definition clone`) from four parallel create streams.

## What happens

Two of the ~140 resources, created 12.6 s apart, came up UpToDate on A and B. Their diskless peers C, D and E never connected (F did). The three had started their handshakes within the same seconds. Hours later, the connections were still cycling.

Diskless side D toward B (CET):

```
07:08:41.149  sock_recvmsg returned -11; conn( Connecting -> BrokenPipe ); short read (expected size 8); timeout while waiting for feature packet
07:08:42.292  conn( Unconnected -> Connecting )
07:08:48.318  timeout while waiting for feature packet
07:08:49.460  conn( Unconnected -> Connecting )
07:08:55.104  Handshake to peer 1 successful: Agreed network protocol version 125; Feature flags enabled …; Peer authenticated using 20 bytes HMAC
07:08:55.104  conn( Connecting -> NetworkFailure ) [disconnected]
07:08:55.113  Connection closed; helper command: /sbin/drbdadm disconnected
07:08:55.188  helper exit code 0; Restarting receiver thread; conn( Unconnected -> Connecting )
07:09:01.236  timeout while waiting for feature packet
```

At the same time, B logs `Handshake to peer 3 successful` and `Preparing cluster-wide state change … conn( Connected )`. 7 ms later B logs `sock was shut down by peer` and then `Aborting cluster-wide state change (7ms) rv = -21; Failure to connect: Interrupted state change (-21); retrying`. In a later cycle toward A, D went from `Peer authenticated` to `NetworkFailure [disconnected]` in 26 µs and 31 µs, with no line in between.

## Part 1: the teardown is b4fa5b19

This matches b4fa5b19 "drbd: fix spurious NetworkFailure right after a successful handshake" (2026-10-05, drbd-9.2 and master). No release tag contains it. Each feature-packet timeout goes through `retry:` in `conn_connect()`. There, `conn_disconnect()` leaves the receiver at RESTARTING (drbd_receiver.c:12002-12006, drbd_main.c:709-719). The next successful handshake then enters `drbdd()`, which exits at once (drbd_receiver.c:11137). Our logs fit this:
- no line between `Peer authenticated` and `NetworkFailure [disconnected]`;
- the next `Connecting` follows 0.08-0.2 s later through a thread restart, not after the 1 s `retry:` sleep;
- on the peer, a closed socket and -21.

## Part 2: why the feature exchange times out

Most attempts end with "timeout while waiting for feature packet" after the 2.0 s receive timeout (`ping_timeo * 4`, drbd_receiver.c:1265). Each side sends its feature packet first, so this side's data socket was not the one the peer sent on. Together with part 1, this gives a loop: after each thread restart, the attempts time out until one succeeds and is torn down. The fixed 1 s sleep after a failed handshake (drbd_receiver.c:1352) has no jitter, so both sides stay in phase. It started only where several diskless peers handshook with a fresh resource in the same seconds.

What we tried on the stuck resources:
1. With C and E StandAlone, D alone still did not connect.
2. Only one path on the diskful side: B still logged handshake, then -21, three times in a minute.
3. `connect-int 10` on the diskful side: no change.
4. `disconnect`/`connect` on D: no change. `down` and `adjust` on D: the connection to B came up within 5 s, but the one to A kept looping.
5. `disconnect`/`connect`, then `adjust`, on A toward D: no change.

## Questions

1. Is b4fa5b19 planned for a 9.3.x release (and a 9.2.x one)?
2. What can make the feature exchange time out on most attempts of a connection whose peer is reachable on the same path, and is it related to several diskless peers handshaking with a new resource at the same time?

We plan to test 9.3.4 + b4fa5b19 on the dev cluster and will report the result here.

---

Prepared with AI assistance at Webdock

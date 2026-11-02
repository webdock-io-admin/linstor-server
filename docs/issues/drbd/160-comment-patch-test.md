Test result with b4fa5b19, on one of the stuck resources from the report.

The diskless node D has run 9.3.4 + b4fa5b19 since 14:51 CET: the PPA 9.3.4 dkms tree with only that patch applied; `drbd_thread_consume_restart` is present in `/proc/kallsyms`. The diskful nodes A and B still run unpatched 9.3.4.

- Before the module swap, D's connection to B had been Connected since the earlier `down`/`adjust` on D (item 4 in the report). The connection to A was still looping.
- After the swap and `adjust` on D, D loops toward both A and B. Every ~6 s it logs "timeout while waiting for feature packet", "short read (expected size 8)" and `Connecting -> BrokenPipe`. D logs no successful handshake, so its data socket never receives the peer's feature packet.
- So the patch on the diskless side alone does not clear an existing loop, and it does not keep a freshly reconnecting diskful peer out of it. That fits question 2 in the report: the feature exchange itself keeps timing out, independent of the teardown that b4fa5b19 fixes.

Then, all times CET:

- **B, `drbdadm down` and `adjust` of this resource at 14:57:15:** B-D did not connect within 60 s. Since D was patched, B logs no handshake toward D at all: no "Handshake successful" and no -21. D keeps logging feature-packet timeouts every ~6 s. Both ends of B-D have now been recreated (D by the module reload and `adjust`, B by `down` and `adjust`), and the feature exchange still never completes. So whatever prevents it survives a fresh start of both ends of that connection.
- **A-D connected at 14:58:26.7**, 71 s after B's `adjust`. This was the first time A and D connected for this resource: protocol 125, HMAC authenticated, `Established` on both volumes, 0 bits out of sync, no BrokenPipe or -21, still Connected after 60 s. Nothing was done on A or D at that moment.
- **B-D** still loops as described above.

With the patch on the diskless side, a connection whose feature exchange completes now stays up. Without the patch, the first handshake to succeed after an in-place retry was torn down, as in the report. The connection whose feature exchange keeps timing out stays down, patched or not. That is question 2 of the report.

**Why the feature exchange times out: the diskful node does not accept() the connections.** Measured read-only at about 15:05 CET with `ss` and `nstat`. Node letters as in the report; C, D and E are the looping diskless peers.

- **On B, the resource's listener on its first-path address has a full accept queue:** Recv-Q 65 against a backlog of 64 (`DRBD_PEERS_MAX * 2`, drbd_transport_tcp.c:1095). It holds 130 sockets from C, D and E, on both paths, that were never `accept()`ed: CLOSE-WAIT, 9 or 89 unread bytes, many without an inode. `TcpExtListenOverflows` = `ListenDrops` = 57,938 and growing by about 1.7/s. B's listener on its second-path address is empty. The listener that B's `down`/`adjust` recreated filled up again.
- **On the diskless nodes C and D, the resource's listeners are empty** (Recv-Q 0, backlog 64, `ListenOverflows` 0). All of D's attempts go out on the first path to B. One of them sits half-open in B's full queue (ESTAB, sent 8 bytes, received 0) beside the 6 s SYN loop. B's own SYNs toward D never reach D (0 SYN-RECV on D).
- **On A, the same pattern is starting.** A accepted D at 14:58, but E holds 10 half-open sockets (FIN-WAIT-2) toward A's first-path listener, and C keeps reconnecting toward both A and B. A's queue has not overflowed yet.

So the peers' feature-packet timeouts are connections that the diskful node never takes off its listen queue.

In 9.3.4, `accept()` happens only in `dtt_wait_for_connect()`, one socket per wake-up, while `pending_accepts > 0` (drbd_transport_tcp.c:773-787). A socket for another path of the same listener is handed to that path (:817-835). `pending_accepts` is raised only from `dtt_incoming_connection()` (:1005-1022). That is the listen socket's `sk_state_change`, which the children inherit until they are accepted. Our guess, from the code only: once the queue is full and the counter has dropped to 0 (for example after a `kernel_accept()` that returned -EAGAIN after the decrement, :780-787), new SYNs are dropped, no child changes state, `pending_accepts` stays 0, and nobody drains the queue.

Questions:
1. Can `pending_accepts` fall out of step with the listen queue, so that the queue still holds connections while `dtt_wait_for_connect()` no longer calls `kernel_accept()`? Is a full backlog of 64 meant to recover by itself?
2. Why only the first path's listener? Every attempt of D used the first path, and B's second-path listener stayed empty.

Next: A gets the patched module at a planned reboot on 2026-11-03, and B later. We will add the results here.

---

Prepared with AI assistance at Webdock

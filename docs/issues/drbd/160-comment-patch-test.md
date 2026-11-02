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

Next: A gets the patched module at a planned reboot on 2026-11-03, and B later. We will add the results here.

---

Prepared with AI assistance at Webdock

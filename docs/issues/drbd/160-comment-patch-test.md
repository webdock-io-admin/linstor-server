Test result with b4fa5b19, on one of the stuck resources from the report.

The diskless node D has run 9.3.4 + b4fa5b19 since 14:51 CET: the PPA 9.3.4 dkms tree with only that patch applied; `drbd_thread_consume_restart` is present in `/proc/kallsyms`. The diskful nodes A and B still run unpatched 9.3.4.

- Before the module swap, D's connection to B had been Connected since the earlier `down`/`adjust` on D (experiment 4b above). The connection to A was still looping.
- After the swap and `adjust` on D, D loops toward both A and B. Every ~6 s it logs "timeout while waiting for feature packet", "short read (expected size 8)" and `Connecting -> BrokenPipe`. D logs no successful handshake, so its data socket never receives the peer's feature packet.
- So the patch on the diskless side alone does not clear an existing loop, and it does not keep a freshly reconnecting diskful peer out of it. That fits question 2 in the report: the feature exchange itself keeps timing out, independent of the teardown that b4fa5b19 fixes.

<pending: result of `down` and `adjust` of this resource on the unpatched diskful node B>

Next: A gets the patched module at a planned reboot on 2026-11-03; we will add that result here.

---

Prepared with AI assistance at Webdock

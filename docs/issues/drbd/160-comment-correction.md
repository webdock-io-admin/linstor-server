Correction: the trigger of this report was our own firewall, not DRBD.

**Cause (ours).** Our firewall on the compute nodes dropped inbound TCP to ports 7341, 7342 and 7343 on every address. Those rules were meant for three of our own services on the management address, but they had no destination-address match. LINSTOR's per-node port allocator (range 7000-16999) gave the two failing resources ports 7341 and 7342 on the diskless nodes. So the diskful nodes' SYNs to those ports were silently dropped: tcpdump on D shows the SYNs arriving and no SYN-ACK, and 41,594 drops were counted. The diskless nodes' own connects to the diskful listeners got through. Every affected connection was therefore blocked in one direction only. We have fixed the rule and moved LINSTOR's port range clear of our service ports. Simultaneous creates, two paths and the other suspects in the report were not the cause.

**What may still be of interest is how DRBD 9.3.4 behaves with a path that is blocked one way (SYNs dropped, not rejected):**

1. **First symptom (at creation):** the diskful side completes the handshake and starts the state change to Connected. The peer then closes the connection 7-12 ms later (-21), with no reason logged. This matches b4fa5b19, as analysed above: a successful handshake after an in-place `retry:` is torn down. Can you confirm that b4fa5b19 covers it? Is it planned for a 9.3.x (and 9.2.x) release?
2. **Later symptom (after resets):** on diskful node B, the resource's listener on the first path had a full accept queue (Recv-Q 65 against backlog 64). It held 130 connections from the diskless peers that were never `accept()`ed, and `ListenOverflows` grew by about 1.7/s. A listener recreated by `down`/`adjust` filled up again. We sampled B's stuck receivers 30 times over 6 s: all 30 were in the outbound connect (`dtt_try_connect()` → `inet_stream_connect` → `wait_woken`), none in `dtt_wait_for_connect()`, and the listener's Recv-Q stayed at 65 throughout. The diskless side's own connects reach B, so the pair could complete if B accepted them.

   As we read 9.3.4, each connect should give up after `connect_int` (`sk_sndtimeo = connect_int * HZ`, drbd_transport_tcp.c:504-505, connect at :524), and between connects `dtt_connect()` waits in `dtt_wait_for_connect()`, where it would accept (:773-787). We never caught a receiver there. So either it returns at once (for example when `drbd_listener_try_get_ref()` finds no listener, :752-754, or the wait condition is already true), or the connect blocks for longer than `connect_int` on this kernel. Which is expected, and should the accept path keep draining the queue while the outbound direction is dropped?
3. With b4fa5b19 on the diskless node D, D's connection to diskful node A came up at the first handshake that completed. A's SYNs to D were dropped too (same port on D), but A accepted D's connects. B, with the same blocked direction, did not accept them. That contrast is why we ask question 2.

Suggestion: the LINSTOR documentation could mention that the automatic DRBD port range must stay clear of ports that a host firewall filters on any address.

Sorry for the noise in the original report.

---

Prepared with AI assistance at Webdock

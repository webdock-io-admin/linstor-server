Follow-up with a concurrency sweep on 1.35.2, <node: stock satellite, node letter to confirm>. 40 clones of one 10 GiB source per cell, at client concurrency 1, 2, 4 and 8:

| Concurrency | Throughput (clones/min) | Client latency p50 / p99 | Per clone (p50 / concurrency) |
|---|---|---|---|
| 1 | 7.1 | 8.4 / 10.8 s | 8.4 s |
| 2 | 6.1 | 19.7 / 21.5 s | 9.9 s |
| 4 | 5.3 | 44.0 / 49.8 s | 11.0 s |
| 8 | 4.7 | 101.2 / 110.7 s | 12.7 s |

- **Concurrency buys nothing.** Throughput falls slightly as concurrency rises, and latency grows linearly with it (p50 ≈ concurrency × 60 / throughput in every cell). The clones are served one at a time.
- **About 8 DeviceManager cycles per clone in every cell, one clone step per cycle.** A clone waits 0.01 s from `Begin` to its first work, so the time goes into the cycles that follow, not into the initial queue.
- **The cycle cost tracks the object count:** 1.00 s per cycle at 282 datasets on the node, 1.55 s at 522. The `zfs` calls stayed at about 15 ms each. `drbdadm` per cycle grew from 0.32 to 0.53 s. 8 cycles × 1.00-1.55 s is the 8.4-12.7 s per clone in the table.

So a burst of N clones of one source costs about N × 8 cycles, and each cycle gets slower as the number of resources grows. This is the same question as 2 in my earlier comment: could several clones of the same source share DeviceManager cycles (or the steps of one cycle), so that a burst does not scale with N × 8 cycles? The per-cycle cost is the part #561 addresses for the ZFS listings. In this run, the growth is mostly in the `drbdadm` time per cycle.

---

Prepared with AI assistance at Webdock

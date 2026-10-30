Follow-up to my comment above, on the provenance part (confirming the previous comment for the PPA packages).

On node A (drbd-dkms 9.3.4-1ppa1~noble1 and drbd-utils 9.35.0-1ppa1~noble1, both from ppa:linbit/linbit-drbd9-stack, kernel 7.2.11-zabbly+) the kernel logs this on **every attach we can see**, for every resource and volume: 711 such lines since 2026-10-01. For example R3, the resource from my comment, at its re-attach after A's own reboot (CET):

```
2026-10-29T17:17:02+01:00 drbd R3/0 drbd1057: Meta data written without the bitmap provenance feature; all out-of-sync bits toward node(s) 0x1 count as set with reason
2026-10-29T17:17:02+01:00 drbd R3: susp-io( no -> quorum ) [attach]
2026-10-29T17:17:02+01:00 drbd R3/0 drbd1057: disk( Diskless -> Attaching ) [attach]
2026-10-29T17:17:02+01:00 drbd R3/0 drbd1057: disk( Attaching -> Outdated ) [attach]
2026-10-29T17:17:02+01:00 drbd R3/0 drbd1057: attached to current UUID: 180A8100A1E49746
```

(The same for R3/1. Node 0x1 is B, the peer toward which A held 2533701 bits on 2026-10-28. The attach before that event is older than A's journal goes back for this resource, so this is the pattern, not that exact attach.)

From the sources:

- The message comes from `distrust_unsupported_peer_flags()` in `drbd_nl.c` (tag drbd-9.3.4, line 3377 ff.). It fires when the on-disk features lack `DRBD_MDFF_BITMAP_AUTHORITATIVE`, and then marks every peer slot with a bitmap as `MDF_PEER_BITMAP_AUTHORITATIVE`, i.e. all standing bits count as "set with reason". The message is not in drbd-9.3.3 or drbd-9.3.2, so the feature is new in 9.3.4.
- drbd-utils does not know the feature yet, also on current master (`v9.35.0-28-gc3c722a2`): `user/shared/drbdmeta.c:316` has `#define DRBD_MD_FEATURES (DRBD_MDFF_DIVERGENCE_BITMAP | DRBD_MDFF_CBT)`, and on every write drbdmeta keeps only those (`drbdmeta.c:773-776`, `disk->features.be = cpu_to_be64(cpu->features & DRBD_MD_FEATURES)`). Any drbdmeta write before an attach (for example `apply-al`) therefore drops the 9.3.4 flag, and the kernel then distrusts the bitmap provenance on the next attach.

Questions:

1. Is a drbd-utils release that keeps `DRBD_MDFF_BITMAP_AUTHORITATIVE` (adds it to `DRBD_MD_FEATURES`) planned? Until then, is 9.3.4 with drbd-utils 9.35.0 a supported pairing?
2. As we read it, the flag only changes how *standing* bits are treated, so a full resync after an attach should only follow if the bitmap toward that peer was already large, as here (2533701 bits toward a peer that had been Established). Is that right, and is there a safe way to clear such stale bits toward an UpToDate peer with equal UUIDs without a full resync?
3. Is there a workaround on the utils side until then (e.g. avoiding the drbdmeta write before attach)?

---

Prepared with AI assistance at Webdock

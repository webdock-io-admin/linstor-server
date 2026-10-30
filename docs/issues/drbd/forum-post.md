Title: DRBD 9.3.4: three resync/reconnect findings on a LINSTOR cluster with diskless primaries (issues #157, #158, comment on #153)

Setup for all three: DRBD 9.3.4 (drbd-dkms 9.3.4-1ppa1~noble1, GIT-hash e59b287b), drbd-utils 9.35.0-1ppa1~noble1 from ppa:linbit/linbit-drbd9-stack, kernel 7.2.11-zabbly+ (Ubuntu 24.04), LINSTOR 1.35.2, ZFS thin. Two diskful storage nodes (A, B) per resource, diskless Primaries running VMs, two network paths per connection, `quorum majority`.

**1. Diskless peer goes StandAlone on an exposed-UUID mismatch during a multi-path reconnect** (https://github.com/LINBIT/drbd/issues/157). After one network path to a diskful node went down, the peer rejoined over the second path. For one volume it was accepted as "older data"; for the other volume of the same resource the diskless node logged "Current UUID of peer does not match my exposed UUID" and went StandAlone, with no split-brain message. Workaround: `drbdadm connect <res>:<peer>` on the diskless node a few minutes later connects with matching UUIDs and no resync.

**2. Resync never concludes after a refused second handshake** (https://github.com/LINBIT/drbd/issues/158). After a storage-node reboot, a small bitmap resync starts; within 100 ms a second handshake ("Becoming WFBitMapS because primary is diskless") is refused with -8 and postponed, and for a few resources the running resync then makes no progress (SyncTarget/Inconsistent, rs-in-flight 0, even with 0 bits). `invalidate` either picks a diskless peer and sets no bits, or is refused with -8. Workaround: delete and re-create the replica in LINSTOR, i.e. a full resync for a residue of a few KB.

**3. Full-device resync of an UpToDate node via the equal-UUID reconciliation** (comment on https://github.com/LINBIT/drbd/issues/153). During a peer removal, "no-sync by rule=lost-quorum" was overridden by "reconcile equal-UUID peers holding out-of-sync bits": the UpToDate node A became SyncTarget for its whole 10 GiB device. A logs "Meta data written without the bitmap provenance feature; all out-of-sync bits toward node(s) … count as set" on every attach we can see, which fits the drbdmeta case described in #153. No workaround needed so far; the resync completes.

Questions to the community: has anyone seen these on 9.3.x, is there a known fix or a recommended in-band recovery for 2 (instead of re-creating the replica), and is the drbd-utils 9.35.0 / drbd 9.3.4 pairing from the PPA expected to log the provenance message on every attach?

Prepared with AI assistance at Webdock

# DRBD 9.3.4 + b4fa5b19: dev-rack test (built from source on the node)

`b4fa5b19.patch` in this directory is `git format-patch -1 b4fa5b19` from LINBIT/drbd master. Upstream commit: b4fa5b19dbafd6ef740ecc9dca07bdc9beccbe91, "drbd: fix spurious NetworkFailure right after a successful handshake". Its sha256 is in SHA256SUMS. It applies to tag drbd-9.3.4 (e59b287b1) with line offsets only. The drbd/ sources in the PPA package drbd-dkms 9.3.4-1ppa1~noble1 are identical to that tag, which we checked file by file.

V=9.3.4-1ppa1~noble1, NV=9.3.4-1ppa1~noble1+webdock1

## Build (diskless node D first; the running module is untouched)

1. `dpkg --verify drbd-dkms` prints nothing, so the installed PPA source tree is unmodified. Then check the patch: `sha256sum -c SHA256SUMS`.
2. `cp -a /usr/src/drbd-$V /usr/src/drbd-$NV`
   `patch -p1 --no-backup-if-mismatch -d /usr/src/drbd-$NV/src < b4fa5b19.patch`
   This patches 3 files: drbd_int.h, drbd_main.c, drbd_receiver.c.
   `sed -i "s/^PACKAGE_VERSION=.*/PACKAGE_VERSION=\"$NV\"/" /usr/src/drbd-$NV/dkms.conf`
   `diff -r /usr/src/drbd-$V /usr/src/drbd-$NV` should show only the 3 source files and the dkms.conf version line.
3. `dkms add -m drbd -v $NV && dkms build -m drbd -v $NV && dkms install -m drbd -v $NV --force`
   Check: `modinfo -n drbd` is under `updates/dkms`; `dkms status drbd` lists $NV as installed for 7.2.11-zabbly+.
   If the build fails, stop: the loaded module is untouched. Undo with `dkms remove -m drbd -v $NV --all`, then `dkms install -m drbd -v $V --force`.

## Switch the module on D

4. Stop the instances on D, then `systemctl stop linstor-satellite`, so that it does not re-adjust while the module is out.
5. `drbdadm down all`. Check: `drbdsetup status` prints nothing.
6. `rmmod drbd_transport_tcp drbd_transport_lb-tcp drbd` (add `drbd_transport_rdma` if `lsmod` lists it), then `modprobe drbd`.
7. Check that `grep -w drbd_thread_consume_restart /proc/kallsyms` prints a line; that symbol exists only with the patch. `/proc/drbd` still shows 9.3.4 with GIT-hash e59b287b…, by design.
8. `systemctl start linstor-satellite`, or `drbdadm adjust all` if needed.
9. Effect: the formerly stuck A-D connection reaches Connected. The kernel log no longer shows `conn( Connecting -> NetworkFailure ) [disconnected]` right after `Peer authenticated`.

## Diskful node A (Tuesday's planned reboot)

Before the reboot, steps 1-3. After it, steps 7-9. If it boots a different kernel, run `dkms install -m drbd -v $NV` for that kernel.

## Rollback (either node)

`dkms remove -m drbd -v $NV --all`, then `apt install --reinstall drbd-dkms=$V`, which rebuilds the PPA module. Then reload the module (steps 4-6 and 8) or reboot.

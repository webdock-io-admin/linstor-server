# Dev-rack upgrade LINSTOR 1.35.2 → 1.35.3 (one window)

Roles: **CTRL** = controller VM; **A, B** = storage satellites, where **P** is the one running the patched jars; **C-F** = compute satellites (diskless). Rehearse the whole sequence on the isolated test controller first.

## Facts this plan rests on

- The controller accepts a satellite only on an **exact** version match (`VersionInfoProviderImpl.equalsVersion`, major.minor.patch). A satellite on the other version shows `VERSION_MISMATCH` and gets no updates; DRBD keeps running. So everything moves in one window, with no creates or deletes during it.
- Packages (ppa:linbit/linbit-drbd9-stack, noble): `linstor-common`, `linstor-controller` and `linstor-satellite`, all `1.35.3-1ppa1~noble1`. Controller and satellite depend on `linstor-common (=` same version`)`. The client (`linstor-client`, `python-linstor` 1.29.1) is unchanged.
- No SQL/etcd migration in 1.35.3. The only new migration is on the K8s-CRD backend.
- The satellite package's postinst restarts `linstor-satellite` on upgrade. The controller package does the same for its service.
- 1.35.3 installs `lib/satellite-1.35.3.jar` and `lib/server-1.35.3.jar`. Any differently named jar left in `/usr/share/linstor-server/lib/` stays there next to them.
- Expect one `adjust` pass per resource after the satellites reconnect. `.res` files change only where controller or node-connection peer-device (`disk`) options are set (1.35.3 fix to ConfFileBuilder).

## 0. Before the window (T-30 min)

1. Stop all Incus create, delete and clone activity and the test drivers. Check: `linstor r l` shows no transitional states. `linstor err l --since 1h` gives the baseline.
2. On every node: `apt-cache policy linstor-common linstor-controller linstor-satellite`, then `ls /var/cache/apt/archives/linstor-*1.35.2*`. The 1.35.2 debs must still be in the apt cache for rollback.
3. On **P**: `ls -l /usr/share/linstor-server/lib/satellite-* /usr/share/linstor-server/lib/server-*` and `dpkg -V linstor-satellite linstor-common`. Note which files are the patched 1.35.2 jars (`dev-16-jars-v2`: satellite `e5a57b74…`, server `3994ef1e…`). Any patched jar whose name the package does not own must be removed at step 3.2.
4. On **CTRL**: `systemctl stop linstor-controller`, then `/usr/share/linstor-server/bin/linstor-database export-db -c /etc/linstor /root/linstor-db-pre-1.35.3.json.gz` (in 1.35.2 it is not on PATH), then `chmod 600 /root/linstor-db-pre-1.35.3.json.gz` (it is written 0644), then `systemctl start linstor-controller`. Check: the file is non-empty and mode 600, and `linstor n l` shows all nodes Online again. Rehearsal on the small test controller: 3.4 s, 114 KB. Expect longer and larger on the rack.

## 1. Controller

`apt-mark unhold linstor-common linstor-controller`
`apt-get install linstor-common=1.35.3-1ppa1~noble1 linstor-controller=1.35.3-1ppa1~noble1`
`apt-mark hold linstor-common linstor-controller`

Check: `linstor controller version` reports 1.35.3. `linstor n l` shows every satellite as `VERSION_MISMATCH` (expected until step 2). Rehearsal: install 8.8 s, REST API back 0.6 s after it, no migration ran, and node and resource-definition lists were identical before and after. If the API is not back within about 30 s, check `journalctl -u linstor-controller` before going on.

## 2. Satellites, one at a time (C, D, E, F, then the storage node that is not P, then P)

Per node:
`apt-mark unhold linstor-common linstor-satellite`
`apt-get install linstor-common=1.35.3-1ppa1~noble1 linstor-satellite=1.35.3-1ppa1~noble1`
`apt-mark hold linstor-common linstor-satellite`

Check before the next node:
- `linstor n l` shows this node `Online`.
- `journalctl -u linstor-satellite --since -5min` shows the full sync done, with no exceptions.
- `drbdsetup status` on the node: all resources in the same state as before (Connected/UpToDate). A short reconnect is acceptable; a lasting `StandAlone` or `Connecting` is not.
- `linstor err l --since 10min`: no new error reports.

## 3. Patched jars on P (`dev-16-jars-1.35.3-v2`)

1. Fetch and check: `gh release download dev-16-jars-1.35.3-v2 -R webdock-io-admin/linstor-server`, then `sha256sum -c SHA256SUMS`.
   - satellite-1.35.3-3-eb2e9e420.jar: `12a9494ba6a8caaa7eb17db9d949b97d22ab89add1d00fc3948fc900d9d112bc`
   - server-1.35.3-3-eb2e9e420.jar: `8aef8529504ce104f05bb0a987dd9ed038024a1d520e43f987faa229b42e5943`
2. `systemctl stop linstor-satellite`. Copy the stock `lib/satellite-1.35.3.jar` and `lib/server-1.35.3.jar` to `/root/stock-1.35.3/`. Overwrite both files, keeping those names, with the two jars. Remove any leftover patched 1.35.2 jar from step 0.3.
3. `systemctl start linstor-satellite`.

Check:
- `sha256sum /usr/share/linstor-server/lib/{satellite,server}-1.35.3.jar` gives the two hashes above.
- `ls /usr/share/linstor-server/lib/ | grep -c -E '^(satellite|server)-'` is 2.
- P is Online.
- Rack trace as in `docs/pr/16-verify.md`: no pool-wide `zfs list`/`zfs get` while idle.

The package is held, so no apt run replaces the jars. `dpkg -V linstor-satellite linstor-common` will report the two files as modified; that is expected.

## 4. After the window

1. **Node-connection paths (1.35.3 fix):** for one storage/compute pair that shares no resource, restart the compute node's satellite, wait for Online, then create a test resource on the pair. Check: `linstor node-connection path list <storage> <compute>` still lists both paths, and `drbdsetup show <test-res>` has two paths with the two fabric addresses, not the default interface. Delete the test resource.
2. All nodes Online at 1.35.3. `linstor r l` matches the pre-window state. `linstor err l --since 1h` shows nothing new. On every node `drbdsetup status` is all Connected/UpToDate.
3. One clone and one delete through the normal path, then the create-path smoke test.

## Rollback

- **Patched jars only (P):** `systemctl stop linstor-satellite`, copy the two files back from `/root/stock-1.35.3/`, `systemctl start linstor-satellite`. The fallback build `dev-16-jars-1.35.3` (fix 16 without #545) is also available.
- **Whole cluster to 1.35.2:** in the same window, reverse order (P and the other storage node, then C-F, then CTRL). On each node: `apt-mark unhold …`, then `apt-get install --allow-downgrades linstor-common=<1.35.2 version> linstor-satellite=<…>` (or `linstor-controller`) from `/var/cache/apt/archives/`, then `apt-mark hold …`. On P, put `dev-16-jars-v2` back. Rehearsal (controller, from the cached debs): 8.9 s, node and resource-definition lists identical afterwards. No database restore is needed: 1.35.3 made no SQL/etcd schema change. Keep `/root/linstor-db-pre-1.35.3.json.gz` regardless.

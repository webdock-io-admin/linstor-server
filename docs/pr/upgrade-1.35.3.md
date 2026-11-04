# Dev-rack upgrade LINSTOR 1.35.2 → 1.35.3, then the fix-20 test on node P (Wednesday 2026-11-04)

Roles:
- **CTRL**: the controller VM.
- **P**: the storage node for the jar test.
- **A**: the other storage node.
- **C-F**: the compute satellites (diskless).
- **TEST-CTRL**: the isolated test controller used for the rehearsal on 2026-11-02.

Why fix 20 matters: the planned standing fleet is about 1,900 VMs, about 3,900 zvols per storage node plus snapshots. That is the 8K-dataset scale, far beyond where the stock satellite's per-cycle cost is acceptable (see LINBIT/linstor-server#565).

## Sequence (CET)

| Time | Step | Done by | Check |
|---|---|---|---|
| 07:00-08:30 | Upgrade every node to 1.35.3 on **stock** jars (sections 0-2) | controller and each node's agent | per node: `linstor n l` Online; `linstor r l` shows no new non-UpToDate |
| 08:30-09:30 | Stock control cell on P, then the v3 jars on P (section 3) | P's agent | the release note's checks |
| 09:30 | Decision: v3 or stock | coordinator | |
| after 09:30 | If v3: the same jar swap on A | A's agent | same checks |
| ~10:30 | Standing-fleet build | compute nodes | fleet check green |
| fallback | If fix 20 misbehaves: stock jars, cold sets cut to 400 per node | coordinator | |

Precondition from the night before: the earlier test leaves the pair at its baseline object count, about 300-400 resource definitions.

## Facts this plan rests on

- The controller accepts a satellite only on an **exact** version match (`VersionInfoProviderImpl.equalsVersion`, major.minor.patch). A satellite on the other version shows `VERSION_MISMATCH` and gets no updates; DRBD keeps running. So everything moves in one window, with no creates or deletes during it.
- Packages (ppa:linbit/linbit-drbd9-stack, noble): `linstor-common`, `linstor-controller` and `linstor-satellite`, all `1.35.3-1ppa1~noble1`. Controller and satellite depend on `linstor-common (=` same version`)`. The client (`linstor-client`, `python-linstor` 1.29.1) is unchanged. **The PPA now carries only 1.35.3**, so the 1.35.2 debs for a rollback must come from each node's apt cache or a saved copy. `apt-get download` cannot fetch them any more.
- No SQL/etcd migration in 1.35.3. The only new migration is on the K8s-CRD backend.
- The satellite and controller packages restart their service in postinst on upgrade.
- 1.35.3 installs `lib/satellite-1.35.3.jar` and `lib/server-1.35.3.jar`. The start scripts use `CLASSPATH=$APP_HOME/lib/conf:$APP_HOME/lib/*` (bin/Satellite), so **every** jar in `/usr/share/linstor-server/lib/` is loaded. Patched jars must therefore overwrite the two stock names, and exactly one jar of each kind may be in `lib/`.
- Expect one `adjust` pass per resource after the satellites reconnect. `.res` files change only where controller or node-connection peer-device (`disk`) options are set (1.35.3 fix to ConfFileBuilder).
- "Failed to load Sentry configuration" also appears when 1.35.2 starts. It is not new; ignore it.

## 0. Preflight (T-30 min)

1. Stop all create, delete and clone activity and the test drivers. Check: `linstor r l` shows no transitional states. `linstor err l --since 1h` gives the baseline.
2. On **every** node:
   - `apt-mark showhold | grep linstor`: note per node whether the packages are held. **Do not assume.** One compute node had no holds on 2026-11-03.
   - `apt-cache policy linstor-common linstor-controller linstor-satellite`.
   - `ls /var/cache/apt/archives/linstor-*1.35.2*`, or copy the 1.35.2 debs to `/root/debs-1.35.2/`. They are needed for a rollback, and the PPA no longer has them.
3. On **P** (and A if it also runs patched jars): `ls -l /usr/share/linstor-server/lib/satellite-* /usr/share/linstor-server/lib/server-*` and `dpkg -V linstor-satellite linstor-common`. Note any patched 1.35.2 jars (`dev-16-jars-v2`: satellite `e5a57b74…`, server `3994ef1e…`). Any differently named one must be removed after the package upgrade, so that only the two stock-named jars remain.
4. Note the state to compare against: the hashes of `linstor n l` and of `linstor rd l` (names and properties).
5. On **CTRL**: `systemctl stop linstor-controller`, then `/usr/share/linstor-server/bin/linstor-database export-db -c /etc/linstor /root/linstor-db-pre-1.35.3.json.gz` (in 1.35.2 it is not on PATH), then `chmod 600 /root/linstor-db-pre-1.35.3.json.gz` (it is written 0644), then `systemctl start linstor-controller`. Check: the file is non-empty and mode 600, and `linstor n l` shows all nodes Online again. Rehearsal on TEST-CTRL: 3.4 s, 114 KB. Expect longer and larger on the rack.

## 1. Controller

`apt-mark unhold linstor-common linstor-controller` (only if step 0.2 showed them held)
`apt-get install linstor-common=1.35.3-1ppa1~noble1 linstor-controller=1.35.3-1ppa1~noble1`
`apt-mark hold linstor-common linstor-controller`

Check: `linstor controller version` reports 1.35.3 (GIT 75d5fa72). `linstor n l` shows every satellite as `VERSION_MISMATCH` (expected until step 2). Rehearsal: install 8.8 s, REST API back 0.6 s after it, no migration ran, and node and resource-definition lists were identical before and after. If the API is not back within about 30 s, check `journalctl -u linstor-controller` before going on.

## 2. Satellites, one at a time (C-F, then A, then P), on stock jars

Per node:
`apt-mark unhold linstor-common linstor-satellite` (only if step 0.2 showed them held)
`apt-get install linstor-common=1.35.3-1ppa1~noble1 linstor-satellite=1.35.3-1ppa1~noble1`
`apt-mark hold linstor-common linstor-satellite`

On P or A, if step 0.3 found a differently named patched jar: `systemctl stop linstor-satellite`, remove it, `systemctl start linstor-satellite`.

Check before the next node:
- `linstor n l` shows this node `Online` at 1.35.3.
- `ls /usr/share/linstor-server/lib/ | grep -c -E '^(satellite|server)-'` is 2.
- `journalctl -u linstor-satellite --since -5min` shows the full sync done, with no exceptions.
- `drbdsetup status` on the node: all resources in the same state as before. A short reconnect is acceptable; a lasting `StandAlone` or `Connecting` is not.
- `linstor err l --since 10min`: no new error reports.

After the last node, the hashes of `linstor n l` and `linstor rd l` (names and properties) match step 0.4.

## 3. Node-P test: v3 jars (`dev-16-jars-1.35.3-v3-r2`; the first v3 upload lacked the version metadata)

1. **Control cell first**, on stock 1.35.3: one create-rate cell (~50 clones at the current object count). Also count the set-gi calls per skip-init volume; expect 32.
2. Check the jars against the release: `sha256sum -c SHA256SUMS`.
   - satellite-1.35.3-4-b2f114305.jar: `21cdeb94fb0d65955c422b8f7ba5a0069a2be50446710fe045e207517bfdc3d7`
   - server-1.35.3-4-b2f114305.jar: `7f593a360b8afb66e5e75ddbe053960b076ddc499a90bbe68a21b4e85fd5d1aa`
   - `unzip -p server-1.35.3-4-b2f114305.jar version-info.properties` shows `version=1.35.3-4-b2f114305`.
3. Swap: `systemctl stop linstor-satellite`. Copy the stock `lib/satellite-1.35.3.jar` and `lib/server-1.35.3.jar` to `/root/stock-1.35.3/`. Overwrite **those two files, keeping those names**, with the v3 jars. Then `systemctl start linstor-satellite`. Stop, copy, start, so that the satellite never runs with mixed jars.
4. Check:
   - `sha256sum /usr/share/linstor-server/lib/{satellite,server}-1.35.3.jar` gives the two v3 hashes.
   - Exactly 2 jars match `^(satellite|server)-`.
   - P is Online and its resources are clean.
5. The release note's checks, with the counting commands in the standby notes:
   - `drbdmeta --force <minor> v09 <dev> internal dump-md` of a skip-init volume made by stock vs one made by v3, with the day-0/random UUIDs normalised. Expected: identical.
   - set-gi per skip-init volume: 32 → 1. A race winner stays at 32.
   - The same create-rate cell as in step 1.

The package is held, so no apt run replaces the jars. `dpkg -V linstor-satellite linstor-common` will report the two files as modified; that is expected.

`docs/pr/16-verify.md` describes the same swap for 1.35.2. Its hashes (`2733f0e1…`, `96ed6cc6…`) and its rollback directory `/var/backups/linstor-545/` hold **1.35.2** jars and are stale for 1.35.3. Jars from there would put the node in `VERSION_MISMATCH`.

## 4. After the window

1. **Node-connection paths (1.35.3 fix):** for one storage/compute pair that shares no resource, restart the compute node's satellite, wait for Online, then create a test resource on the pair. Check: `linstor node-connection path list <storage> <compute>` still lists both paths, and `drbdsetup show <test-res>` has two paths with the two fabric addresses, not the default interface. Delete the test resource.
2. All nodes Online at 1.35.3. `linstor r l` matches the pre-window state. `linstor err l --since 1h` shows nothing new. On every node `drbdsetup status` is all Connected/UpToDate.

## Rollback, per node

- **v3 → stock jars (P or A):** `systemctl stop linstor-satellite`, copy the two files back from `/root/stock-1.35.3/`, `systemctl start linstor-satellite`. There is no data change: fix 20 only drops redundant set-gi calls. Do **not** use `dev-16-jars-1.35.3` (v1), `-v2` or `-v3` as an alternative. Their server jar has no `version-info.properties`, so the satellite reports `<Unknown>` and the controller rejects it. Use `dev-16-jars-1.35.3-v3-r2` for the test and stock for rollback.
- **1.35.3 → 1.35.2:** in reverse order (P and A, then C-F, then CTRL), one node at a time. On each: `apt-mark unhold …` → `dpkg -i` the kept 1.35.2 debs (`/var/cache/apt/archives/` or `/root/debs-1.35.2/`) → `apt-mark hold …`. On P, put `dev-16-jars-v2` back, the only patched jars for 1.35.2. Rehearsal (controller): 8.9 s, API back in 0.4 s, node and resource-definition lists identical. No database restore is needed, because 1.35.3 made no SQL/etcd schema change. If the database is ever suspect, `linstor-database import-db` from the export in step 0.5.

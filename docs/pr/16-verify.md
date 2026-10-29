# Rack verification of fix 16 (scoped ZFS listings)

Goal: find out which of the three changes removes the idle host-wide
`zfs list` (about 9 per minute in the baseline trace), and confirm that the
satellite behaves the same otherwise. One storage node only, satellite restart
only.

## Build under test

Prerelease `dev-16-jars` on webdock-io-admin/linstor-server:

| Asset | sha256 |
|---|---|
| `satellite-1.35.2-2-ef5f6d525.jar` | `e050049bd164d2887676a6cbc1b337ce166fce98615391fc2ae4f7d8290f961a` |
| `server-1.35.2-2-ef5f6d525.jar` | `79162acade8f22ddaa9d63bc7078f8bb0ca5a25d86bf047e043326920bcd5895` |

Both jars come from one build of the v1.35.2 tag (4f9bfb0a3) plus two
cherry-picked code commits. The resulting build commit ef5f6d525 is local and
not on the fork:

1. 69aef6448: #545 ZFS destroy retry (the fix the rack satellites already
   carry). It is in the satellite jar.
2. f68dc96b0: fix 16. `Commands.genericExecutorLimiter` is in the server jar;
   `ZfsUtils`, `ZfsCommands` and `ZfsProvider` are in the satellite jar.

Both jars must be replaced together. The server jar carries `Commands`, and
mixing jars from different builds breaks the satellite (see the earlier
NoSuchMethodError in the #551 test build). The controller and the other
satellites stay stock: the change touches no API, protobuf or database code,
and the version stays 1.35.2.

Tests of this build: satellite module 126 and ZFS utils 17, 0 failures.

## Install (storage node A, satellite only)

1. Don't use this on a node that also runs the controller.
2. Note the current files and their hashes. The satellite jar should start
   with `2733f0e1` (#545 jar) and the server jar with `96ed6cc6` (stock):
   `sha256sum /usr/share/linstor-server/lib/satellite-*.jar /usr/share/linstor-server/lib/server-*.jar`
3. Check that the rollback copies exist in `/var/backups/linstor-545/`.
4. Download both assets and check them with `sha256sum`.
5. Replace `/usr/share/linstor-server/lib/satellite-*.jar` and
   `/usr/share/linstor-server/lib/server-*.jar` with the two files. Keep the
   original file names, or remove the old files so that only one jar of each
   kind is in `lib/`.
6. `systemctl restart linstor-satellite`. Wait until `linstor node list` shows
   the node Online and `linstor resource list` shows no new errors for its
   resources.

## Trace (same procedure as the baseline)

Same node, same load as `results/scale8k-baseline-20261029`: 60 s idle, then
one resource create, one snapshot, one delete, within 2 minutes.

```
pid=$(systemctl show -p MainPID --value linstor-satellite)
timeout 120 strace -f -qq -v -e trace=execve -s 512 -o /tmp/stlt-execve-16.txt -p "$pid"
```

Count per pattern, for the baseline and for the patched trace:

```
f=/tmp/stlt-execve-16.txt
# 1: zfs list ... -t volume,snapshot with no dataset after "volume,snapshot"
grep -c '"zfs", "list", "-r", .*"-t", "volume,snapshot"\]' $f
# 2: recursive marked_for_deletion get on the pool dataset itself
grep -c '"linstor:marked_for_deletion", "<pool>"\]' $f
# 3: thin pool space query with -r
grep -c '"zfs", "list", "-r", .*"-t", "filesystem"' $f
# all zfs / zpool calls, for the totals
grep -c '"zfs"\|"zpool"' $f
```

Replace `<pool>` with the pool dataset of the storage pool, e.g. the value of
`StorDriver/ZPoolThin`.

Expected with the patched build:
- (1) 0: no `zfs list` without a dataset argument.
- (2) 0: `zfs get -r … linstor:marked_for_deletion` names volumes or snapshots,
  never the pool.
- (3) 0: `zfs list -d 0 … -t filesystem <pool>` instead.
- Per-resource `zfs list -r … <volumes>` still runs, about as often as before.
- Create, snapshot and delete succeed. The deleted resource's zvol is gone
  afterwards, and so is its `CF_*` snapshot once no clone uses it.

To say which change removed which call, keep the full lines of (1) from the
baseline and check the process tree around them: (1) disappearing while the
same API traffic continues means the empty-batch case (`fetchAllocatedSizes`
for a thin pool without matching volumes, or a batch of exactly 1000).
Please also record which API call or client caused it, if visible
(e.g. `linstor resource list`, the Prometheus exporter, or a CSI/Incus poll).

## Rollback

1. Copy the two files from `/var/backups/linstor-545/` back to
   `/usr/share/linstor-server/lib/` (satellite jar `2733f0e1…`, server jar
   `96ed6cc6…`) and remove the dev-16 jars.
2. `systemctl restart linstor-satellite`.
3. Check the hashes and that the node is Online.

The apt hold stays as it is during the test and after the rollback.

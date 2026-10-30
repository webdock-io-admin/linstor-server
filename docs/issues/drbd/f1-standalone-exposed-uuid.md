Title: 9.3.4: diskless peer goes StandAlone on "Current UUID of peer does not match my exposed UUID" for one volume while the other volume of the same resource is accepted as older data (multi-path reconnect)

## Environment

- DRBD kernel module 9.3.4 (api:2/proto:118-125), GIT-hash e59b287b199f7224468af85b68a10e83c68213f5, drbd-dkms 9.3.4-1ppa1~noble1 from ppa:linbit/linbit-drbd9-stack, DKMS-built
- drbd-utils 9.35.0-1ppa1~noble1
- Kernel 7.2.11-zabbly+, Ubuntu 24.04
- LINSTOR 1.35.2 (client 1.29.1) generates the configuration; consumer is the Incus LINSTOR driver
- Resource with 2 volumes: `quorum majority`, `allow-two-primaries yes`, `cram-hmac-alg sha1`, `verify-alg sha512`,
  `c-fill-target 2048s`, `c-max-rate 4194304k`, `c-min-rate 0k`. On the diskless nodes both volumes have
  `disk none`, block-size 4096, discard-granularity 8192.
- Every connection has two paths (two separate networks, "leaf A" and "leaf B").

## Topology

| Node | node-id | Role |
|---|---|---|
| S1 | 0 | diskful, UpToDate |
| S2 | 1 | diskful, UpToDate |
| D2 | 2 | diskless, Secondary (the log below) |
| D1 | 4 | diskless, Primary, writing (runs the VM) |

## What happened (times CET, 2026-10-28)

The test took down leaf A on S2 (port down, address removed), so D2 and S2 had to reconnect over the second path.

1. 10:08:30: PingAck timeout D2→S2. Both volumes `pdsk UpToDate → DUnknown`, `repl Established → Off`, conn
   `NetworkFailure`, then `Connecting`.
2. 10:08:40-41: a cluster-wide state change committed via S1 (`primary_nodes=10`). D2 set exposed data UUIDs vol0
   `61C4691FCDE5098E`, vol1 `414382FDB242F6B4` ("receiver updated UUIDs"); state change `primary_nodes=10`,
   `weak_nodes=FFFFFFFFFFFFFFEE`.
3. 10:08:49: S2 joins (protocol 125):
   - vol0: my exposed UUID `61C4691FCDE5098E`, peer
     `6FD0EAA1FD033680:0000000000000000:43F031E3B6ACBDF6:6E03F615BD67718A bits:0 flags:1120` →
     "Downgrading joining peer's disk as its data is older";
   - vol1: my exposed UUID `414382FDB242F6B4`, peer
     `1DA9603259365DF6:0000000000000000:89B90CF550438EEC:44F7F9E9A4E2A82A bits:0 flags:1120` →
     "Current UUID of peer does not match my exposed UUID." → "Aborting remote state change 1632007276", conn
     `Connecting → Disconnecting [receive-disconnect] → StandAlone [disconnected]`.
   - No split-brain message. `drbdsetup status` on D2: the S2 connection `StandAlone`, role `Unknown`, both
     volumes `replication:Off peer-disk:DUnknown`; the S1 connection stays `Connected/Established/UpToDate`.
4. 10:11:30: manual `drbdadm connect <res>:S2` on D2 (`StandAlone → Unconnected → Connecting`).
5. 10:11:47: Connected, both volumes UpToDate, `bits:0`, no resync. Peer vol0
   `61C4691FCDE5098E:6FD0EAA1FD033680:43F031E3B6ACBDF6:6E03F615BD67718A`, vol1
   `414382FDB242F6B4:1DA9603259365DF6:89B90CF550438EEC:44F7F9E9A4E2A82A`, `flags 1100`;
   `pdsk DUnknown → UpToDate`, `repl Off → Established`.

UUIDs at the join and after the manual connect:

| Volume | D2 exposed (new) | S2 current at 10:08:49 | S2 current at 10:11:47 (bitmap UUID) |
|---|---|---|---|
| vol0 | `61C4691FCDE5098E` | `6FD0EAA1FD033680` | `61C4691FCDE5098E` (`6FD0EAA1FD033680`) |
| vol1 | `414382FDB242F6B4` | `1DA9603259365DF6` | `414382FDB242F6B4` (`1DA9603259365DF6`) |

At 10:08:49 the joining peer's bitmap UUID was 0 on both volumes. At 10:11:47 its bitmap UUID was the old current
UUID and the history UUIDs were unchanged; flags went from 1120 to 1100.

### Both sides

Clocks are NTP-synced (D2 +379 µs, S2 −20 µs). S2 aborted the join `[connect-failed]` at 10:08:49.628337; D2 logged
`[receive-disconnect]` 0.8 ms later, and both were StandAlone within 2 ms. At the join S2 already had D2's new
exposed UUIDs, but it received the Primary D1's new UUIDs only 3 s later (10:08:52.468, vol0 `61C4691FCDE5098F`,
vol1 `414382FDB242F6B5`) over its connection to D1. So S2 rejoined D2 over one connection before it had learned
the new current UUID over another.

## Questions

1. Is it intended that a diskless peer refuses the join and goes StandAlone on an exposed-UUID mismatch for vol1,
   while vol0 of the same resource is accepted as "older data" in the same handshake, with no split-brain message?
2. Is this expected with multi-path reconnects in 9.3: the peer rejoining over the second path before it learned
   the new current UUID from another connection? Would it be better to retry the connect instead of going
   StandAlone, since a plain `drbdadm connect` 3 minutes later succeeded with matching UUIDs and no resync?

Related, possibly the same area of UUID handling on reconnect: #153.

---

Prepared with AI assistance at Webdock

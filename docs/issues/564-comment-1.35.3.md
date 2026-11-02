Checked against v1.35.3 (75d5fa72d): unchanged.

- `ConfFileBuilder.build()` (satellite/.../layer/drbd/resfiles/ConfFileBuilder.java) still writes the layer-data secret into the resource-level `net { }` (`:224-226`, `rscDfnData.getSecret()`). The `DrbdOptions/Net` properties follow it (`appendConflictingDrbdOptions(... NAMESPC_DRBD_NET_OPTIONS ...)`, `:228-234`). Each connection's `net { }` gets the properties again (`:356-364`). The 1.35.2 → 1.35.3 diff of this file touches only the peer-device `disk { }` section.
- The layer-data secret is still set only from the creation payload (`RscDrbdLayerHelper.java:210`, `drbdRscDfnData.setSecret(drbdRscDfnPayload.sharedSecret)`). Setting `DrbdOptions/Net/shared-secret` does not change it. The 1.35.3 diff does not touch `RscDrbdLayerHelper`, `DrbdRscDfnData` or `StltLayerRscDataMerger`.

The repro in the report is unchanged: `resource-definition set-property <rd> DrbdOptions/Net/shared-secret <new>` gives three `shared-secret` lines, and the old one stays in the file.

What would solve it for us, either way:
- the generated secret line reflects the current secret: setting the property also updates the layer-data secret, or the property replaces the generated line in the resource-level `net { }`; or
- the old line is dropped from the `.res` when the secret is rotated, so that only one effective secret remains per section.

---

Prepared with AI assistance at Webdock

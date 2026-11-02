Title: Setting DrbdOptions/Net/shared-secret on a resource definition leaves the old generated secret in the .res: three shared-secret lines, old one still in the file

**Environment:** LINSTOR 1.35.2 (same code on master e1d99946e), DRBD 9.3.4, drbd-utils 9.35.0.

**What we did:** we rotated the secret of one resource with `linstor resource-definition set-property <rd> DrbdOptions/Net/shared-secret <new>`. The OpenAPI spec documents this property for resource definitions, resource connections, resource groups and the controller.

**What happens:** the generated `.res` then has three `shared-secret` lines:
- in the resource-level `net { }`, the old secret from the DRBD layer data (`ConfFileBuilder.java:224-226`, `rscDfnData.getSecret()`), followed by the new one from the property (`appendConflictingDrbdOptions(... NAMESPC_DRBD_NET_OPTIONS ...)`, `:228-234`);
- in each connection's `net { }`, the new one again (`:356-364`).

drbdadm uses the last one, and the reconnect with the new secret works (0.52 s). But the old secret stays in the file text, and in the layer data it came from.

**Expected:** one effective secret per section. Either the property replaces the generated secret in the resource-level `net { }`, or setting the property updates the layer-data secret.

**Questions:**
1. Is `DrbdOptions/Net/shared-secret` the intended way to rotate a resource's secret, or is there another one (for example a way to regenerate the layer-data secret)?
2. If the property is deleted later, the resource-level `net { }` falls back to the old layer-data secret. Is that intended?

---

Prepared with AI assistance at Webdock

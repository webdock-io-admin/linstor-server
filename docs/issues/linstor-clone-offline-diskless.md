Title: Clone fails when a node holding only a DISKLESS resource of the source resource definition is offline; clones inherit diskless placements

**Environment:** LINSTOR 1.35.2 (same code in v1.35.3), ZFS_THIN, `UseZFSClone`. Clones are made from image resource definitions, which have diskful replicas on two storage nodes and diskless resources on the compute nodes that have used them.

**What happened (2026-11-02/03, times CET):** at 11:26-11:30 one compute node C, which holds only a DISKLESS resource of the image's resource definition, was offline for a reboot. During that time, three `resource-definition clone` requests from that image failed. The controller's error reports (6AE34079-00000-000205, -206, -207) say "No connection to satellite '<C>'". The clients saw only a decode error of the error body (see LINBIT/golinstor#22 and #560). Both storage nodes holding the data were online.

**Code path (v1.35.3, `CtrlRscDfnApiCallHandler`):**
- `cloneRscDfnInTransaction()` collects every resource of the source resource definition (`iterateResource()`, :1584-1588). `removeInactiveResources()` drops only `INACTIVE` ones (:1396-1399), so diskless resources stay in. For each of them it runs `failIfWrongRscState()` and `setSuspendIO()` (:1591-1596).
- For each source resource it then creates a resource of the clone on the same node, with the source's flags (`Resource.Flags.restoreFlags(...)`, :1598-1605). A diskless source resource gives a diskless clone resource.
- The suspend is pushed with the default not-connected handler, which only warns. Then `setCloneSnapshotPropertyInTrans()` sets the snapshot-for-clone property on the diskful resources only, but pushes with `updateSatellites(srcRscDfn, notConnectedError(), …)` to every node of the resource definition (:1026-1045). An offline node with a diskless resource fails that step, and the flux ends in `onErrorResume` → `resumeIOAndClearCloneProp()`, which again uses `notConnectedError()` (:1102-1116). So the clone fails.

**Effect:** once a few compute nodes have used an image, every clone of that image depends on all of those compute nodes being online, although none of them holds data. Each clone also adds a diskless resource on each of those nodes, so the set grows with every clone.

**Questions:**
1. Could snapshot-for-clone skip diskless source resources, or only warn for them when their node is offline, since they hold no data to snapshot? The suspend of I/O on a diskless Primary would still need the node, so maybe only when it is not Primary.
2. Is it intended that a clone inherits the source's diskless placements? For a clone meant to start somewhere else, creating resources only for the diskful source resources, with diskless ones created on demand, would avoid both the dependency and the growth.

Related: #562 (clone bursts, CF_ snapshots), LINBIT/golinstor#22 and #560 (the error body the clients could not decode).

---

Prepared with AI assistance at Webdock

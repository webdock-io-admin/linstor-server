# Clone serialisation: scope of the controller's RSC_DFN_MAP write lock (read-only)

Code: v1.35.2 (4f9bfb0a3, the rack build); master has the same structure. Read 2026-10-30.
Question (from the u18r8 measurement, 6-6.5 s per clone regardless of parallelism): is the controller's
`RSC_DFN_MAP` write lock held across the satellite round trip of each clone step?

## Answer: no, only for the in-memory change, the DB commit and building the update notices

1. Each clone step is a `scopeRunner.fluxInTransactionalScope(..., lockGuard(read NODES_MAP, write
   RSC_DFN_MAP), callable)`: `setCloneSnapshotProperty` (`CtrlRscDfnApiCallHandler.java:1006-1016`),
   `resumeIOAndClearCloneProp` (`:1082-1093`), `removeStartCloning` (`:1143-1152`, write on
   `RSC_DFN_MAP` and `NODES_MAP`). The callable changes props/flags, calls `ctrlTransactionHelper.commit()`
   and returns `ctrlSatelliteUpdateCaller.updateSatellites(rscDfn, …)` as a Flux.
2. `ScopeRunner.doInScope` (`server/.../apicallhandler/ScopeRunner.java:156-208`) takes the lock, runs
   the callable and releases the lock in `finally` as soon as the callable *returns* the Flux, before
   anything subscribes to it.
3. `updateSatellites(rscDfn, …)` (`CtrlSatelliteUpdateCaller.java:193-213`) iterates the resources and
   builds one small "changed" notice per node while the lock is held. The send is
   `TcpConnectorPeer.apiCall` (`:462-487`), a `Flux.create` that serialises the call frame and sends only
   on subscription, i.e. after the unlock. The satellites then fetch the changed data with their own
   requests, which the controller answers under read locks.

So per clone step the write lock covers milliseconds of work (property/flag changes, one DB commit,
notice building). Many clones still serialise on these short sections, because the lock is the whole
`RSC_DFN_MAP`, not the one resource definition, but that does not explain 6 s per clone.

## More likely: the step chain on the satellites

`cloneRscDfnInTransaction` (`:1463`, chain at `:1690-1704`) chains, per clone: suspend IO on the source → set the clone-snapshot property
(the satellite creates the CF_ snapshot in a DeviceManager run) → resume IO and clear the property →
deploy the clone resources (`CLONING_START`) → the satellite's `CloneService` runs `zfs clone` /
send|receive → `CLONING_FINISHED` notification → `removeStartCloning`. Each arrow is a controller → satellite
→ controller round trip, and each satellite step needs a DeviceManager run. The DeviceManager is a single
thread per satellite. Pending dispatches are batched into the next run (`DeviceManagerImpl.java:915-933`),
but one run must finish before the next starts, and every run of a ZFS resource pays the listing cost
(fix 16 reduces that). With about 6 steps, ~1 s per step, from DRBD state changes (suspend/resume) plus the run
time, would give the measured 6-6.5 s (hypothesis, to be confirmed with the logs below). Parallel clones of the same source share the source satellite's runs.
They get batched but do not shorten each other's chains, which matches "regardless of parallelism".

## How to confirm on the rack (no patch needed)

- Controller at TRACE for one clone burst: `scope 'Set clone snapshot property' start/end` (and the other
  scope names above) gives the exact lock-hold time per step. Expected: milliseconds.
- Source and target satellites: `Begin/End DeviceManager cycle` timestamps during the burst: number of
  runs per clone and their length. Expected: ~5-6 runs per clone, the clone's time spent mostly between
  runs (waiting for the next step's update) and inside runs (listing, drbdadm).
- Compare with the dev-16-v2 satellite: if the runs get noticeably shorter on a pool with many datasets,
  the clone time drops by (number of steps × saved run time).

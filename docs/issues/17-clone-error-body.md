Title: REST error bodies are not always an ApiCallRc list: clone errors (HTTP 500) return a ResourceDefinitionCloneStarted object, and the OpenAPI spec documents a third shape

**Environment:** LINSTOR controller 1.35.2 (same code on master e1d99946e), REST API v1, the Go client golinstor as used by the Incus LINSTOR storage driver.

**What happens:** a clone failed on the controller, and
`POST /v1/resource-definitions/{rsc}/clone` answered HTTP 500. The client
(Incus via golinstor) reported only

```
Error: Failed instance creation: Failed creating instance from image: Unable to start cloning resource definition: json: cannot unmarshal object into Go value of type client.ApiCallError
```

The controller's actual error was lost, because golinstor decodes every
non-2xx body as a list of `ApiCallRc` and this body is an object.

Observed twice on a 6-node dev cluster (times as logged, UTC):

- 2026-10-19 20:13-20:14, 10 parallel instance launches from one image. The
  load balancer logged the clone POST as 500, 1776 bytes, 42.9 s. The
  controller logged `Failed to access DRBD super-block of volume <clone>/0`
  for storage node B, then `Exceptions have been converted to responses`, and
  marked the resource definition failed. The error report shows a
  `DelayedApiRcException` in stage "Finish clone update" with the suppressed
  `ApiRcException` "(node B) Failed to access DRBD super-block of volume
  <clone>/0", numeric code -4611686018427386906. That entry is what the
  1776-byte body carried.
- 2026-10-28 21:57:20: 500 with 2222 bytes after 4.9 s, then a retry 500 with
  847 bytes ("non-UpToDate DRBD device").

Neither the controller nor the client logs response bodies, so the body below
is reconstructed from the code, not captured.

**Shapes the controller returns for errors (master e1d99946e):**

| Where | HTTP | Body |
|---|---|---|
| `LinstorMapper.toResponse` (`GrizzlyHttpService.java:880-956`), all unhandled exceptions; `ApiCallRcRestUtils.toResponse` / `mapToMonoResponse` for regular API calls | 400/404/405/500 | `ApiCallRcList` (JSON array) |
| `POST /v1/resource-definitions/{rsc}/clone`, `ResourceDefinitions.mapToCloneStarted` (`ResourceDefinitions.java:321-349`) | 500 if `flatApiCallRc.hasErrors()`, else 201 | `ResourceDefinitionCloneStarted` object: `{location, source_name, clone_name, messages: [ApiCallRc…]}` |
| `GET /v1/files/{extFileName}/status/{node}` (`ExternalFiles.java:171-175`), satellite not connected | 503 | `{"message": "Satellite not connected"}` |
| `POST /v1/nodes/exec/drbd-reactorctl/*` (`Nodes.java:762-802`), invalid request | 400 | `{"error": "'nodes' must be a non-empty list"}` / `{"error": "'config' is required"}` |

The 500 for clone errors was introduced by 9d96b8001 (2025-09-29, CHANGELOG:
"Clone api: errors are now indicated with HTTP status code 500"). Before
that, errors came with 201, so clients read the object as a success body.
The body shape stayed the object.

**Spec:** `docs/rest_v1_openapi.yaml` documents clone `'500'` as
`#/components/responses/OperationFailed`, whose schema is `ApiCallRc`, a
**single object** with `ret_code` and `message`. The controller sends neither
that nor the `ApiCallRcList` it uses everywhere else. `OperationFailed` is
referenced by other operations too, and for those the actual body is the
list, so the spec's `ApiCallRc` there looks like it should be `ApiCallRcList`.

**Shape of the clone error body**, reconstructed from
`Json.resourceDefCloneStarted` with the error entry of the first case:

```json
{
  "location": "/v1/resource-definitions/src/clone/dst",
  "source_name": "src",
  "clone_name": "dst",
  "messages": [
    {
      "ret_code": -4611686018427386906,
      "message": "(node B) Failed to access DRBD super-block of volume <clone>/0",
      "error_report_ids": ["<report id>"]
    }
  ]
}
```

**Question / proposal:**
1. Is the object body for clone errors intended (clients should read
   `.messages` on 500)? If so, please document it: clone `'500'` →
   `ResourceDefinitionCloneStarted`. If not, return `ApiCallRcList` like the
   other endpoints.
2. Correct `OperationFailed` to `ApiCallRcList`, or say which endpoints really
   return a single `ApiCallRc`.
3. Consider returning `ApiCallRcList` for the `ExternalFiles` 503 and the
   drbd-reactorctl 400 as well, so a client can decode every error body the
   same way.

On the client side we propose a tolerant decode for golinstor (list, then an
object with `messages`, then the raw body kept in the error); we would offer
that to golinstor separately.

**Steps to reproduce:** make a clone fail on the controller side (in our
case a DRBD super-block error on one node during 10 parallel clones). Then
look at the HTTP status and body of `POST /v1/resource-definitions/{rsc}/clone`
with `curl -i`.

---

Prepared with AI assistance at Webdock; reviewed by <engineer>

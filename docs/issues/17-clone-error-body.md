Title: REST error bodies are not always an ApiCallRc list: clone errors (HTTP 500) return a ResourceDefinitionCloneStarted object, and the OpenAPI spec documents a third shape

**Environment:** LINSTOR controller 1.35.2 (same code on master e1d99946e), REST API v1, the Go client golinstor as used by the Incus LINSTOR storage driver.

**What happens:** a clone failed on the controller with an unhandled
`PeerClosingConnectionException`. `POST /v1/resource-definitions/{rsc}/clone`
answered HTTP 500, and golinstor reported only

```
json: cannot unmarshal object into Go value of type client.ApiCallError
```

The controller's actual error was lost, because golinstor decodes every
non-2xx body as a list of `ApiCallRc` and this body is an object.

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

**Shape of the clone error body.** This is reconstructed from
`Json.resourceDefCloneStarted`, not captured; the fields of the error entry
are illustrative:

```json
{
  "location": "/v1/resource-definitions/src/clone/dst",
  "source_name": "src",
  "clone_name": "dst",
  "messages": [
    {
      "ret_code": "<masked error code>",
      "message": "<controller error message>",
      "error_report_ids": ["<id>"]
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

**Steps to reproduce:** make a clone fail on the controller side, e.g. clone
from a resource whose satellite drops the connection during the clone. Then
look at the HTTP status and body of `POST /v1/resource-definitions/{rsc}/clone`
with `curl -i`.

---

Prepared with AI assistance at Webdock; reviewed by <engineer>

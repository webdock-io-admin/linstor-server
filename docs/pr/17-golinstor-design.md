# Design: tolerant error-body decoding in golinstor (report 17)

Status: design for whoever holds a golinstor fork. Not implemented. **The
golinstor source was not read for this design** (outside the author's scope
until a fork is set up), so the hook location below is described by what to
search for, not by file:line. Verify it against golinstor before applying.

Related LINSTOR issue draft: `docs/issues/17-clone-error-body.md` (the
controller's error-body shapes and the OpenAPI mismatch).

## Problem

For a non-2xx response golinstor decodes the body as a list of `ApiCallRc`.
The error text `json: cannot unmarshal object into Go value of type
client.ApiCallError` shows that the decode target is the type
`client.ApiCallError` itself, i.e. a slice type (`[]ApiCallRc`), and that the
body was a JSON object. The decode error replaces the controller's error, so
the caller only sees the JSON message.

The controller returns an object for errors in three places (details and
file:line in the issue draft):

- `POST /v1/resource-definitions/{rsc}/clone`, HTTP 500:
  `{location, source_name, clone_name, messages: [ApiCallRc…]}`. This is the
  case Incus hit.
- `GET /v1/files/{f}/status/{node}`, HTTP 503: `{"message": "…"}`.
- `POST /v1/nodes/exec/drbd-reactorctl/*`, HTTP 400: `{"error": "…"}`.

Any proxy or load balancer in front of the controller can also answer with a
non-JSON body (HTML 502/504).

## Change

1. **Where:** the code that turns a non-2xx `*http.Response` into an error.
   Search golinstor's `client` package for `ApiCallError` and for the
   `json.Unmarshal` / `json.NewDecoder(...).Decode` call on the response body
   in the non-2xx branch (likely in the central request function, e.g. `do`
   in `client.go`). Replace that one decode with a call to `decodeErrorBody`.
2. **The function** (new, unexported, next to `ApiCallError`):

```go
// decodeErrorBody turns the body of a non-2xx response into an error. The
// controller usually sends a list of ApiCallRc, but some endpoints send an
// object (clone: ResourceDefinitionCloneStarted with "messages"; others:
// {"message": ...} or {"error": ...}), and proxies may send anything. The
// controller's information must never be lost to a decode error.
func decodeErrorBody(statusCode int, body []byte) error {
	var list ApiCallError
	if err := json.Unmarshal(body, &list); err == nil && len(list) > 0 {
		return list
	}

	var obj struct {
		Messages ApiCallError `json:"messages"`
		Message  string       `json:"message"`
		Error    string       `json:"error"`
	}
	if err := json.Unmarshal(body, &obj); err == nil {
		switch {
		case len(obj.Messages) > 0:
			return obj.Messages
		case obj.Message != "":
			return ApiCallError{{RetCode: maskError, Message: obj.Message}}
		case obj.Error != "":
			return ApiCallError{{RetCode: maskError, Message: obj.Error}}
		}
	}

	return &UnexpectedErrorBody{StatusCode: statusCode, Body: truncate(body, 4096)}
}
```

   - `maskError`: the value golinstor uses for a generic error return code.
     If there is none, use the `MASK_ERROR` bit from LINSTOR's `ApiConsts`
     (`0xC000000000000000`). That keeps `ApiCallError.Is(...)`-style checks on
     the error bit working. Field names follow golinstor's `ApiCallRc` struct;
     check them.
   - `UnexpectedErrorBody` (new, exported so callers can `errors.As` it):

```go
type UnexpectedErrorBody struct {
	StatusCode int
	Body       string
}

func (e *UnexpectedErrorBody) Error() string {
	return fmt.Sprintf("unexpected error response (HTTP %d): %s", e.StatusCode, e.Body)
}
```

   - `truncate` caps the kept body (4 KiB) so an HTML error page does not
     flood logs.
3. **Behaviour that must not change:** a list body still returns exactly
   the same `ApiCallError` value as today; 2xx handling is untouched; callers
   that type-assert `ApiCallError` keep working for every body that has
   `ApiCallRc` entries. Only the cases that fail today return something new.
4. Read the body once (`io.ReadAll`) before decoding, if the current code
   decodes straight from the stream, so that the raw body is still there for
   the fallback.

## Tests (table-driven, in the client package)

| Case | Body | HTTP | Expected |
|---|---|---|---|
| list | `[{"ret_code":-4611686018427386906,"message":"boom"}]` | 500 | `ApiCallError` with that entry, same as today |
| clone object | `{"location":"/v1/…","source_name":"a","clone_name":"b","messages":[{"ret_code":…,"message":"boom","error_report_ids":["X"]}]}` | 500 | `ApiCallError` with the `messages` entry, report id kept |
| message object | `{"message": "Satellite not connected"}` | 503 | `ApiCallError` with one entry, message kept, error bit set |
| error object | `{"error": "'nodes' must be a non-empty list"}` | 400 | `ApiCallError` with one entry |
| empty list | `[]` | 500 | `*UnexpectedErrorBody` with body `[]` (not a nil error) |
| HTML | `<html>502 Bad Gateway</html>` | 502 | `*UnexpectedErrorBody`, body kept |
| empty | `` | 500 | `*UnexpectedErrorBody`, empty body |
| huge | 1 MiB of text | 500 | `*UnexpectedErrorBody`, body truncated to 4 KiB |

Plus one test through the real request path with `httptest.NewServer`
returning the clone object with 500, asserting that the clone call's error is
an `ApiCallError` whose message is the controller's message.

## Controller side (recommendation)

Keep the controller as it is for now. The object body on clone errors is
deliberate (9d96b8001) and clients may already read `.messages`. Ask LINBIT in
the linstor-server issue to document it, or to switch all error bodies to
`ApiCallRcList`. The client change above is needed either way, for older
controllers and for proxies.

## PR text outline (golinstor)

- Title: `client: do not lose the controller's error when the error body is not a list`
- Body: the Incus clone failure with the message above; the three object
  shapes with the controller file:line from the issue draft; the decode order;
  the tests; the link to the linstor-server issue.
- Last line: "Prepared with AI assistance at Webdock; reviewed and tested by <engineer>"

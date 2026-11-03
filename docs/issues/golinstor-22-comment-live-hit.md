A live case of what this PR fixes, from 2026-11-03 (LINSTOR 1.35.2, golinstor as used by our client):

Three `resource-definition clone` requests failed while one node holding a resource of the source resource definition was offline. The client got only

```
Unable to start cloning resource definition: json: cannot unmarshal object into Go value of type client.ApiCallError
```

The controller's actual error was "No connection to satellite '<node>'" (error reports 6AE34079-00000-000205, -206, -207). It never reached the caller, because the clone error body is a `ResourceDefinitionCloneStarted` object, not an `ApiCallRc` list (LINBIT/linstor-server#560). With this PR, the client returns the controller's message instead. The cause of the clone failure itself is described in LINBIT/linstor-server#566.

---

Prepared with AI assistance at Webdock

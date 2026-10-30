Manage and call Oracle Cloud Infrastructure Functions (serverless) from Kestra flows.

## What OCI Functions is

An OCI *function* is a small container image that Oracle runs on demand. It belongs to an *application* and is called through an
*invoke endpoint* specific to that function. This plugin calls functions that are already deployed. It does not contain function code and does not build images.

## Tasks and trigger

| Component | Use it to |
|---|---|
| `GetFunction` | read a function: state, memory, timeout, `invokeEndpoint` |
| `ListFunctions` | list the functions of an application (follows pagination; filters: `displayName`, `lifecycleState`, `limit`) |
| `InvokeFunction` | call a function with an optional `body`; the response is `outputs.<id>.body` (or `uri` above 1 MiB) |
| `CreateFunction` | register an image already pushed to the OCI Registry as a function in an application |
| `DeleteFunction` | delete a function (cannot be undone) |
| `FunctionInvocationTrigger` | invoke a function every `interval` and start an execution with its response (`{{ trigger.body }}`) |

## Calling a function

`InvokeFunction` only needs the function OCID. It looks up the function's invoke endpoint itself, so there is no URL to configure.

```yaml
id: invoke_oci_function
namespace: company.platform

tasks:
  - id: invoke
    type: io.kestra.plugin.oci.functions.InvokeFunction
    authType: CONFIG_FILE
    functionId: "{{ secret('OCI_FUNCTION_OCID') }}"
    body:
      recordId: 42
      action: process

  - id: log_response
    type: io.kestra.plugin.core.log.Log
    message: "Function response: {{ outputs.invoke.body }}"
```

- `body` is a string, or an object that is sent as JSON. Leave it out to call without input.
- `invokeType: DETACHED` returns as soon as the function has started, with no response.
- The function must be `ACTIVE`. Use `GetFunction` to check its `lifecycleState`.

## Required permissions

The identity you authenticate with needs an IAM policy that allows it to use the function family, for example:

```
Allow group <group> to use fn-function in compartment <compartment>
Allow group <group> to read fn-app in compartment <compartment>
Allow group <group> to manage fn-function in compartment <compartment>   # only for CreateFunction and DeleteFunction
```

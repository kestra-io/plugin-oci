Orchestrate Oracle Cloud Infrastructure (OCI) from Kestra flows.

## Authentication

Every task and trigger of this plugin signs its requests with the OCI SDK. Choose how with `authType`:

- `API_KEY` (default): give `region`, `tenancyOcid`, `userId`, `fingerprint` and `privateKey` (and `passphrase` if the key is encrypted). Store them as Kestra secrets.
- `CONFIG_FILE`: read an OCI config file (`configFile`, default `~/.oci/config`) and a `profile` (default `DEFAULT`). The file must exist on the machine that runs the task.
- `INSTANCE_PRINCIPAL`: use the identity of the OCI compute instance Kestra runs on. No other property is needed.
- `RESOURCE_PRINCIPAL`: use the identity of the OCI resource Kestra runs in, such as a function. No other property is needed.

```yaml
tasks:
  - id: get
    type: io.kestra.plugin.oci.functions.GetFunction
    region: eu-frankfurt-1
    tenancyOcid: "{{ secret('OCI_TENANCY_OCID') }}"
    userId: "{{ secret('OCI_USER_OCID') }}"
    fingerprint: "{{ secret('OCI_FINGERPRINT') }}"
    privateKey: "{{ secret('OCI_PRIVATE_KEY') }}"
    functionId: "{{ secret('OCI_FUNCTION_OCID') }}"
```

## Sub-plugins

- **Functions** (`io.kestra.plugin.oci.functions`): get, list, invoke, create and delete OCI Functions, and start executions from the response of a function.

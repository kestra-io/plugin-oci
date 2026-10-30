<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
  <a href="https://twitter.com/kestra_io" style="margin: 0 10px;">
        <img src="https://kestra.io/twitter.svg" alt="twitter" width="35" height="25" /></a>
  <a href="https://www.linkedin.com/company/kestra/" style="margin: 0 10px;">
        <img src="https://kestra.io/linkedin.svg" alt="linkedin" width="35" height="25" /></a>
  <a href="https://www.youtube.com/@kestra-io" style="margin: 0 10px;">
        <img src="https://kestra.io/youtube.svg" alt="youtube" width="35" height="25" /></a>
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 3 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 3 minutes.</i></p>

# Kestra Oracle Cloud Infrastructure Plugin

## Why

- Calling OCI from a flow usually means custom HTTP tasks and hand-made request signing, which is fragile.
- This plugin gives typed tasks and triggers for OCI services, and signs every request with the OCI Java SDK.

## What

Provides plugin components under `io.kestra.plugin.oci`. Today it contains the **Functions** sub-plugin (`io.kestra.plugin.oci.functions`):

| Component | Purpose |
|---|---|
| `GetFunction` | Read a function (state, memory, timeout, invoke endpoint) |
| `ListFunctions` | List the functions of an application |
| `InvokeFunction` | Call a function and return its response |
| `CreateFunction` | Register a container image as a function |
| `DeleteFunction` | Delete a function |
| `FunctionInvocationTrigger` | Invoke a function on an interval and start an execution with the response |

## Usage

Pick how to authenticate with `authType` (default `API_KEY`):

| `authType` | What it uses |
|---|---|
| `API_KEY` | `region`, `tenancyOcid`, `userId`, `fingerprint`, `privateKey` (and `passphrase`) |
| `CONFIG_FILE` | An OCI config file (`configFile`, default `~/.oci/config`) and `profile` (default `DEFAULT`) |
| `INSTANCE_PRINCIPAL` | The identity of the OCI compute instance Kestra runs on |
| `RESOURCE_PRINCIPAL` | The identity of the OCI resource Kestra runs in, for example a function |

```yaml
id: invoke_oci_function
namespace: company.platform

tasks:
  - id: invoke
    type: io.kestra.plugin.oci.functions.InvokeFunction
    region: eu-frankfurt-1
    tenancyOcid: "{{ secret('OCI_TENANCY_OCID') }}"
    userId: "{{ secret('OCI_USER_OCID') }}"
    fingerprint: "{{ secret('OCI_FINGERPRINT') }}"
    privateKey: "{{ secret('OCI_PRIVATE_KEY') }}"
    functionId: "{{ secret('OCI_FUNCTION_OCID') }}"
    body:
      recordId: 42

  - id: log_response
    type: io.kestra.plugin.core.log.Log
    message: "Function response: {{ outputs.invoke.body }}"
```

`InvokeFunction` only needs the function OCID: it looks up the function's invoke endpoint itself. The identity you authenticate with needs
IAM permissions on the function; see [the Functions documentation](src/main/resources/doc/io.kestra.plugin.oci.functions.md).

## Building and testing

Use JDK 21.

```bash
./gradlew build
```

The unit tests run offline against a fake SDK client. `FunctionsIntegrationTest` calls a real OCI tenancy and is **skipped** unless these
environment variables are set:

| Variable | Needed for |
|---|---|
| `OCI_REGION`, `OCI_TENANCY_OCID`, `OCI_USER_OCID`, `OCI_FINGERPRINT`, `OCI_PRIVATE_KEY` | an API signing key (the key's PEM text) |
| `OCI_FUNCTION_OCID` | an `ACTIVE` function to get, list and invoke (it should accept a JSON body) |
| `OCI_APP_OCID` | the application that function belongs to |
| `OCI_FUNCTION_IMAGE` | optional: an image in the OCI Registry, to also test create and delete (it creates a function named `kestra-it-...` and deletes it) |

```bash
export OCI_REGION=eu-frankfurt-1 OCI_TENANCY_OCID=... OCI_USER_OCID=... OCI_FINGERPRINT=... \
       OCI_FUNCTION_OCID=... OCI_APP_OCID=...
export OCI_PRIVATE_KEY="$(cat ~/.oci/oci_api_key.pem)"
./gradlew test --tests '*FunctionsIntegrationTest'
```

## Running Kestra locally with this plugin

1. Build the shadow JAR: `./gradlew shadowJar`. The output lands in `build/libs/`.
2. Run `docker compose up`. `docker-compose.yml` builds `kestra/kestra:latest` and mounts `build/libs/` to `/app/plugins/`, so Kestra picks up the jar on startup.
3. Kestra UI is available at [localhost:8080](http://localhost:8080).

### Plugins folder gotcha

Mounting a host folder onto `/app/plugins/` replaces the container's plugins directory rather than adding to it. Core plugins (the ones logged as `Registered N core plugins`) are compiled into Kestra itself and aren't affected, but any additional plugin normally bundled in the base image under `/app/plugins/` (e.g. the Python script plugin) gets hidden once the mount is in place. If a flow you're testing depends on another plugin, copy its jar into `build/libs/` too before starting the container.

### JFR startup error

On some hosts, `command: server local` fails with:
```
Unable to create JFR repository directory using base location (/tmp)
```
`docker-compose.yml` works around this by mounting `/tmp` as `tmpfs`. If you build your own compose file or run Kestra via `docker run`, add the same workaround, e.g. `-v /tmp:/tmp` or `--tmpfs /tmp`. Tracked upstream in [kestra-io/kestra#17405](https://github.com/kestra-io/kestra/issues/17405).

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.

![Star the repo](https://kestra.io/star.gif)

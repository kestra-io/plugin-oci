# Kestra Oracle Cloud Infrastructure Plugin

## What

- Plugin for Oracle Cloud Infrastructure (OCI), under `io.kestra.plugin.oci`.
- Today it contains one sub-plugin, `functions` (OCI Functions, serverless): five tasks and one trigger.
- More OCI services (Object Storage, Compute, ...) are planned in the EPIC <https://github.com/kestra-io/plugin-oci/issues/2>, each as its own sub-package.

## Why

- Flows can call OCI services as typed tasks, instead of custom HTTP calls with hand-made request signing.
- OCI requests must be signed. The plugin does that through the OCI Java SDK, with four ways to authenticate (see below).

## How

### Architecture

Single-module plugin, SDK `com.oracle.oci.sdk` 3.87.0 (BOM), Kestra `1.3.39`, Java 21.

```
src/main/java/io/kestra/plugin/oci/
├── package-info.java            # @PluginSubGroup: title, CLOUD + INFRASTRUCTURE
├── AbstractOciTask.java         # connection properties shared by every OCI task
├── OciConnectionInterface.java  # the same properties as getters, so a trigger can use them too
├── OciAuthType.java             # API_KEY | CONFIG_FILE | INSTANCE_PRINCIPAL | RESOURCE_PRINCIPAL
├── OciAuth.java                 # turns the properties into an SDK authentication provider
└── functions/                   # one sub-package per OCI service
    ├── package-info.java        # @PluginSubGroup for "Functions"
    ├── AbstractFunctionsTask.java   # creates the SDK clients (overridden by tests)
    ├── FunctionInvoker.java         # invoke logic shared by InvokeFunction and the trigger (package-private)
    ├── GetFunction.java
    ├── ListFunctions.java
    ├── InvokeFunction.java
    ├── CreateFunction.java
    ├── DeleteFunction.java
    └── FunctionInvocationTrigger.java

src/main/resources/
├── metadata/index.yaml, metadata/functions.yaml   # plugin group metadata (the linter requires one per sub-package)
├── icons/plugin-icon.svg, icons/io.kestra.plugin.oci.functions.svg
└── doc/io.kestra.plugin.oci.md, doc/io.kestra.plugin.oci.functions.md

src/test/java/io/kestra/plugin/oci/
├── OciAuthTest.java                         # offline
└── functions/
    ├── *Test.java                           # offline, with a fake SDK client
    └── FunctionsIntegrationTest.java        # real OCI, skipped unless credentials are in the environment
```

### Authentication

Every task and `FunctionInvocationTrigger` has an `authType` property (default `API_KEY`):

| `authType` | Properties used | Notes |
|---|---|---|
| `API_KEY` | `region`, `tenancyOcid`, `userId`, `fingerprint`, `privateKey`, optional `passphrase` | secrets-driven |
| `CONFIG_FILE` | optional `configFile`, `profile` | defaults to `$OCI_CONFIG_FILE`, then `~/.oci/config`, profile `DEFAULT`; the file is read on the worker |
| `INSTANCE_PRINCIPAL` | none | only works on an OCI compute instance; the region is detected |
| `RESOURCE_PRINCIPAL` | none | only works inside an OCI resource such as a function (reads `OCI_RESOURCE_PRINCIPAL_*`) |

`OciAuth.provider(runContext, connection)` is the single place that builds the provider. A trigger is not a `Task`, which is why the
connection properties are an interface; `FunctionInvocationTrigger` declares them again with the same annotations. Change both together.

### Conventions

- Every property is a `Property<T>` and is rendered with `runContext.render(...)`.
- Secrets (`privateKey`, `passphrase`, function `config`) are `@PluginProperty(secret = true)` and excluded from `toString`/`equals`.
- Every property has `@PluginProperty(group = ...)` using the allowed groups of the doc linter; every class has `@Schema` and `@Plugin(examples = ...)`.
- A new OCI service gets its own sub-package with `package-info.java`, `metadata/<name>.yaml` and `icons/io.kestra.plugin.oci.<name>.svg`.
- A task with an SDK client creates it through an overridable method, so tests can pass a fake client (`java.lang.reflect.Proxy` over the SDK interface).
- Test subclasses of a `Task` or trigger must be `public` with a no-arg constructor: Kestra's annotation processor registers them as plugins.

### Build and test

Use JDK 21 (JDK 27 breaks Lombok).

```bash
./gradlew build            # compile, doc lint, tests
./gradlew test             # tests only
```

Integration tests need an OCI tenancy and are skipped without it. See the README for the environment variables.

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Add code for an SDK feature only after checking the SDK sources: most helpers (region lookup, `~` expansion in config paths, paging) already exist.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- OCI Functions API: https://docs.oracle.com/en-us/iaas/api/#/en/functions/latest/

package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsInvoke;
import com.oracle.bmc.functions.FunctionsManagement;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.oci.OciAuth;
import io.kestra.plugin.oci.OciAuthType;
import io.kestra.plugin.oci.OciConnectionInterface;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Start an execution from the response of an OCI function",
    description = "Invokes a deployed OCI function at every `interval` and starts an execution with its response. Use it to react to what a function returns, for example a function that checks a condition and answers with the data to process. A failing call is reported on the trigger and no execution is created."
)
@Plugin(
    examples = {
        @Example(
            title = "Start an execution every 5 minutes with the response of a function",
            full = true,
            code = """
                id: oci_function_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.body }}"

                triggers:
                  - id: poll_function
                    type: io.kestra.plugin.oci.functions.FunctionInvocationTrigger
                    interval: PT5M
                    authType: CONFIG_FILE
                    functionId: ocid1.fnfunc.oc1.eu-frankfurt-1.aaaaaaaaexample
                    body:
                      source: kestra
                """
        )
    }
)
public class FunctionInvocationTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<FunctionInvocationTrigger.Output>, OciConnectionInterface {
    @Schema(title = "Interval between two invocations")
    @Builder.Default
    @PluginProperty(group = "execution")
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(title = "OCID of the function", description = "For example `ocid1.fnfunc.oc1.eu-frankfurt-1.aaaa...`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> functionId;

    @Schema(
        title = "Body sent to the function",
        description = "A string, or a YAML/JSON object that is sent as JSON. Leave it empty to call the function without input."
    )
    @PluginProperty(group = "main")
    private Property<Object> body;

    // The connection properties below are the same as the ones of AbstractOciTask: a trigger cannot extend a task,
    // which is why they are declared through the OciConnectionInterface.

    @Schema(
        title = "Authentication type",
        description = "How to authenticate to OCI. `API_KEY` signs requests with the OCI API signing key given in this trigger's properties. `CONFIG_FILE` reads an OCI config file. `INSTANCE_PRINCIPAL` uses the identity of the OCI compute instance Kestra runs on, and `RESOURCE_PRINCIPAL` that of the OCI resource (for example a function) it runs in; both need no other property, and the region is detected."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<OciAuthType> authType = Property.ofValue(OciAuthType.API_KEY);

    @Schema(
        title = "OCI region identifier",
        description = "For example `eu-frankfurt-1` or `us-ashburn-1`. Required for the `API_KEY` authentication type. With `CONFIG_FILE`, the region of the config file profile is used and this property is not."
    )
    @PluginProperty(group = "connection")
    protected Property<String> region;

    @Schema(title = "OCID of the tenancy", description = "Required for the `API_KEY` authentication type.")
    @PluginProperty(group = "connection")
    protected Property<String> tenancyOcid;

    @Schema(title = "OCID of the calling user", description = "Required for the `API_KEY` authentication type.")
    @PluginProperty(group = "connection")
    protected Property<String> userId;

    @Schema(title = "Fingerprint of the API signing key", description = "Required for the `API_KEY` authentication type.")
    @PluginProperty(group = "connection")
    protected Property<String> fingerprint;

    @Schema(title = "Private key in PEM format", description = "Required for the `API_KEY` authentication type.")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "connection", secret = true)
    protected Property<String> privateKey;

    @Schema(title = "Passphrase of the private key", description = "Only needed if the private key is encrypted. Used by the `API_KEY` authentication type; with `CONFIG_FILE`, set `pass_phrase` in the config file instead.")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "advanced", secret = true)
    protected Property<String> passphrase;

    @Schema(
        title = "Path to the OCI config file",
        description = "For the `CONFIG_FILE` authentication type. Defaults to the file named by the `OCI_CONFIG_FILE` environment variable, then `~/.oci/config`. The file is read on the machine that runs the trigger."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> configFile;

    @Schema(title = "Profile name in the OCI config file", description = "For the `CONFIG_FILE` authentication type. Defaults to `DEFAULT`.")
    @PluginProperty(group = "advanced")
    protected Property<String> profile;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        String rFunctionId = runContext.render(this.functionId).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'functionId' is required"));
        Object rBody = runContext.render(this.body).as(Object.class).orElse(null);

        FunctionInvoker.Result result = FunctionInvoker.invoke(
            runContext, OciAuth.provider(runContext, this), rFunctionId, rBody, InvokeFunction.InvokeType.SYNC, this::client, this::invokeClient
        );

        Output output = Output.builder()
            .statusCode(result.statusCode())
            .body(result.body())
            .uri(result.uri())
            .size(result.size())
            .opcRequestId(result.opcRequestId())
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    // a seam for tests: they return a client that does not call OCI
    protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
        return FunctionInvoker.managementClient(provider);
    }

    protected FunctionsInvoke invokeClient(BasicAuthenticationDetailsProvider provider, String invokeEndpoint) {
        return FunctionInvoker.invokeClient(provider, invokeEndpoint);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "HTTP status code of the call")
        private final Integer statusCode;

        @Schema(title = "Response of the function", description = "Absent when the response is larger than 1 MiB (see `uri`).")
        private final String body;

        @Schema(title = "URI of the stored response", description = "Only set when the response is larger than 1 MiB.")
        private final URI uri;

        @Schema(title = "Size of the response in bytes")
        private final Long size;

        @Schema(title = "OCI request ID", description = "Give it to Oracle support to trace a call.")
        private final String opcRequestId;
    }
}

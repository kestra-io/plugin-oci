package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.FunctionsManagementClient;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.GetFunctionRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.oci.AbstractOciTask;
import io.kestra.plugin.oci.OciAuth;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get an OCI function",
    description = "Fetches the details of a deployed OCI function, including the `invokeEndpoint` used to call it. The function `config` is not returned, as it often holds secrets."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a function with an API signing key",
            full = true,
            code = """
                id: oci_get_function
                namespace: company.team

                tasks:
                  - id: get_function
                    type: io.kestra.plugin.oci.functions.GetFunction
                    region: eu-frankfurt-1
                    tenancyOcid: "{{ secret('OCI_TENANCY_OCID') }}"
                    userId: "{{ secret('OCI_USER_OCID') }}"
                    fingerprint: "{{ secret('OCI_FINGERPRINT') }}"
                    privateKey: "{{ secret('OCI_PRIVATE_KEY') }}"
                    functionId: ocid1.fnfunc.oc1.eu-frankfurt-1.aaaaaaaaexample
                """
        ),
        @Example(
            title = "Get a function with the default OCI config file",
            full = true,
            code = """
                id: oci_get_function_config_file
                namespace: company.team

                tasks:
                  - id: get_function
                    type: io.kestra.plugin.oci.functions.GetFunction
                    authType: CONFIG_FILE
                    functionId: ocid1.fnfunc.oc1.eu-frankfurt-1.aaaaaaaaexample
                """
        )
    }
)
public class GetFunction extends AbstractOciTask implements RunnableTask<GetFunction.Output> {
    @Schema(title = "OCID of the function", description = "For example `ocid1.fnfunc.oc1.eu-frankfurt-1.aaaa...`.")
    @NotNull
    private Property<String> functionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rFunctionId = runContext.render(this.functionId).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'functionId' is required"));

        try (FunctionsManagement client = client(OciAuth.provider(runContext, this))) {
            Function function = client.getFunction(GetFunctionRequest.builder().functionId(rFunctionId).build()).getFunction();

            runContext.logger().info("Function '{}' is {}", function.getDisplayName(), function.getLifecycleState());

            return Output.builder()
                .id(function.getId())
                .displayName(function.getDisplayName())
                .applicationId(function.getApplicationId())
                .compartmentId(function.getCompartmentId())
                .lifecycleState(function.getLifecycleState() == null ? null : function.getLifecycleState().getValue())
                .image(function.getImage())
                .memoryInMBs(function.getMemoryInMBs())
                .timeoutInSeconds(function.getTimeoutInSeconds())
                .invokeEndpoint(function.getInvokeEndpoint())
                .timeCreated(function.getTimeCreated() == null ? null : function.getTimeCreated().toInstant())
                .timeUpdated(function.getTimeUpdated() == null ? null : function.getTimeUpdated().toInstant())
                .freeformTags(function.getFreeformTags())
                .build();
        }
    }

    // a seam for tests: they return a client that does not call OCI
    protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
        return FunctionsManagementClient.builder().build(provider);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "OCID of the function")
        private final String id;

        @Schema(title = "Display name")
        private final String displayName;

        @Schema(title = "OCID of the application the function belongs to")
        private final String applicationId;

        @Schema(title = "OCID of the compartment")
        private final String compartmentId;

        @Schema(title = "Lifecycle state", description = "For example `ACTIVE`. Only an `ACTIVE` function can be invoked.")
        private final String lifecycleState;

        @Schema(title = "Container image of the function")
        private final String image;

        @Schema(title = "Memory in MB")
        private final Long memoryInMBs;

        @Schema(title = "Timeout in seconds")
        private final Integer timeoutInSeconds;

        @Schema(title = "Invoke endpoint", description = "The base URL used to invoke this function. It never changes during the lifetime of the function.")
        private final String invokeEndpoint;

        @Schema(title = "Creation time")
        private final Instant timeCreated;

        @Schema(title = "Last update time")
        private final Instant timeUpdated;

        @Schema(title = "Free-form tags")
        private final Map<String, String> freeformTags;
    }
}

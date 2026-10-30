package io.kestra.plugin.oci.functions;

import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.model.CreateFunctionDetails;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.CreateFunctionRequest;
import com.oracle.bmc.functions.responses.CreateFunctionResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.oci.OciAuth;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create an OCI function",
    description = "Registers a function in an existing OCI Functions application from a container image that is already pushed to the OCI Registry. This task does not build or push the image. The function is created in the `CREATING` state and becomes invokable once it is `ACTIVE`."
)
@Plugin(
    examples = {
        @Example(
            title = "Create a function from an image",
            full = true,
            code = """
                id: oci_create_function
                namespace: company.team

                tasks:
                  - id: create_function
                    type: io.kestra.plugin.oci.functions.CreateFunction
                    authType: CONFIG_FILE
                    applicationId: ocid1.fnapp.oc1.eu-frankfurt-1.aaaaaaaaexample
                    displayName: hello
                    image: fra.ocir.io/mytenancy/functions/hello:0.0.1
                    memoryInMBs: 256
                    timeoutInSeconds: 30
                    config:
                      GREETING: Hello
                """
        )
    }
)
public class CreateFunction extends AbstractFunctionsTask implements RunnableTask<CreateFunction.Output> {
    @Schema(title = "OCID of the application", description = "The OCI Functions application the function is created in.")
    @NotNull
    private Property<String> applicationId;

    @Schema(title = "Display name", description = "Unique within the application.")
    @NotNull
    private Property<String> displayName;

    @Schema(title = "Container image", description = "Qualified name of the image, including its tag, in the OCI Registry of the same region. For example `fra.ocir.io/tenancy/functions/hello:0.0.1`.")
    @NotNull
    private Property<String> image;

    @Schema(title = "Image digest", description = "The digest of the image to pull when the function is invoked. Defaults to the digest currently associated with the image tag.")
    private Property<String> imageDigest;

    @Schema(title = "Memory in MB", description = "Memory allocated to the function. OCI accepts 128, 256, 512, 1024, 2048, 3072 or 4096; when unset, OCI uses its default.")
    private Property<Long> memoryInMBs;

    @Schema(title = "Timeout in seconds", description = "Maximum run time of one call, from 5 to 300. When unset, OCI uses its default.")
    private Property<Integer> timeoutInSeconds;

    @Schema(title = "Function configuration", description = "Key/value pairs given to the function as environment variables. Do not put secrets in a flow definition: pass them with `{{ secret('NAME') }}`.")
    @PluginProperty(group = "advanced", secret = true)
    private Property<Map<String, String>> config;

    @Schema(title = "Free-form tags")
    private Property<Map<String, String>> freeformTags;

    @Override
    public Output run(RunContext runContext) throws Exception {
        CreateFunctionDetails.Builder details = CreateFunctionDetails.builder()
            .applicationId(required(runContext, this.applicationId, "applicationId"))
            .displayName(required(runContext, this.displayName, "displayName"))
            .image(required(runContext, this.image, "image"));
        runContext.render(this.imageDigest).as(String.class).filter(value -> !value.isBlank()).ifPresent(details::imageDigest);
        runContext.render(this.memoryInMBs).as(Long.class).ifPresent(details::memoryInMBs);
        runContext.render(this.timeoutInSeconds).as(Integer.class).ifPresent(details::timeoutInSeconds);
        // an unset map renders as an empty one, which is left out so that OCI applies its own defaults
        Map<String, String> rConfig = runContext.render(this.config).asMap(String.class, String.class);
        if (!rConfig.isEmpty()) {
            details.config(rConfig);
        }
        Map<String, String> rFreeformTags = runContext.render(this.freeformTags).asMap(String.class, String.class);
        if (!rFreeformTags.isEmpty()) {
            details.freeformTags(rFreeformTags);
        }

        try (FunctionsManagement client = client(OciAuth.provider(runContext, this))) {
            CreateFunctionResponse response = client.createFunction(CreateFunctionRequest.builder().createFunctionDetails(details.build()).build());
            Function function = response.getFunction();

            runContext.logger().info("Function '{}' created, state {}", function.getDisplayName(), function.getLifecycleState());

            return Output.builder()
                .id(function.getId())
                .displayName(function.getDisplayName())
                .applicationId(function.getApplicationId())
                .lifecycleState(function.getLifecycleState() == null ? null : function.getLifecycleState().getValue())
                .invokeEndpoint(function.getInvokeEndpoint())
                .opcRequestId(response.getOpcRequestId())
                .build();
        }
    }

    private static String required(RunContext runContext, Property<String> property, String name) throws Exception {
        return runContext.render(property).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'" + name + "' is required"));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "OCID of the new function")
        private final String id;

        @Schema(title = "Display name")
        private final String displayName;

        @Schema(title = "OCID of the application")
        private final String applicationId;

        @Schema(title = "Lifecycle state", description = "Usually `CREATING` right after the call. Use `GetFunction` to check that it became `ACTIVE`.")
        private final String lifecycleState;

        @Schema(title = "Invoke endpoint", description = "The base URL used to invoke this function.")
        private final String invokeEndpoint;

        @Schema(title = "OCI request ID", description = "Give it to Oracle support to trace a call.")
        private final String opcRequestId;
    }
}

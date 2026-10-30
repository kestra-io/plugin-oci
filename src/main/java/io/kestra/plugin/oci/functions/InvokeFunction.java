package io.kestra.plugin.oci.functions;

import com.oracle.bmc.functions.FunctionsInvoke;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.GetFunctionRequest;
import com.oracle.bmc.functions.requests.InvokeFunctionRequest;
import com.oracle.bmc.functions.responses.InvokeFunctionResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.oci.OciAuth;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Invoke an OCI function",
    description = "Calls a deployed OCI function and returns its response. The function must be `ACTIVE`. The plugin looks up the invoke endpoint of the function by its OCID, so only the OCID is required. A response larger than 1 MiB is stored in Kestra's internal storage and returned as a `uri` instead of a `body`."
)
@Plugin(
    examples = {
        @Example(
            title = "Invoke a function without input",
            full = true,
            code = """
                id: oci_invoke_function
                namespace: company.team

                tasks:
                  - id: invoke
                    type: io.kestra.plugin.oci.functions.InvokeFunction
                    authType: CONFIG_FILE
                    functionId: ocid1.fnfunc.oc1.eu-frankfurt-1.aaaaaaaaexample
                """
        ),
        @Example(
            title = "Invoke a function with a JSON body",
            full = true,
            code = """
                id: oci_invoke_function_body
                namespace: company.team

                tasks:
                  - id: invoke
                    type: io.kestra.plugin.oci.functions.InvokeFunction
                    authType: CONFIG_FILE
                    functionId: ocid1.fnfunc.oc1.eu-frankfurt-1.aaaaaaaaexample
                    body:
                      name: Kestra

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.invoke.body }}"
                """
        )
    }
)
public class InvokeFunction extends AbstractFunctionsTask implements RunnableTask<InvokeFunction.Output> {
    // above this size, the response is stored in the internal storage instead of being returned in the output
    private static final long MAX_BODY_BYTES = 1024 * 1024;

    @Schema(title = "OCID of the function", description = "For example `ocid1.fnfunc.oc1.eu-frankfurt-1.aaaa...`.")
    @NotNull
    private Property<String> functionId;

    @Schema(
        title = "Body sent to the function",
        description = "A string, or a YAML/JSON object that is sent as JSON. Leave it empty to call the function without input."
    )
    private Property<Object> body;

    @Schema(
        title = "Invocation type",
        description = "`SYNC` waits for the function to finish and returns its response. `DETACHED` returns as soon as the function has started, with no response body."
    )
    @Builder.Default
    private Property<InvokeType> invokeType = Property.ofValue(InvokeType.SYNC);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rFunctionId = runContext.render(this.functionId).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'functionId' is required"));
        InvokeType rInvokeType = runContext.render(this.invokeType).as(InvokeType.class).orElse(InvokeType.SYNC);
        byte[] requestBody = requestBody(runContext);

        var provider = OciAuth.provider(runContext, this);

        // the invoke endpoint is specific to each function, so it has to be looked up first
        String invokeEndpoint;
        try (FunctionsManagement management = client(provider)) {
            Function function = management.getFunction(GetFunctionRequest.builder().functionId(rFunctionId).build()).getFunction();
            if (function.getInvokeEndpoint() == null || function.getInvokeEndpoint().isBlank()) {
                throw new IllegalStateException("Function '" + rFunctionId + "' has no invoke endpoint, its state is " + function.getLifecycleState());
            }
            invokeEndpoint = function.getInvokeEndpoint();
        }

        InvokeFunctionRequest.Builder request = InvokeFunctionRequest.builder()
            .functionId(rFunctionId)
            .fnInvokeType(rInvokeType == InvokeType.DETACHED ? InvokeFunctionRequest.FnInvokeType.Detached : InvokeFunctionRequest.FnInvokeType.Sync);
        if (requestBody != null) {
            request.invokeFunctionBody(new ByteArrayInputStream(requestBody));
        }

        try (FunctionsInvoke invoke = invokeClient(provider, invokeEndpoint)) {
            InvokeFunctionResponse response = invoke.invokeFunction(request.build());

            Output output = read(runContext, response);
            runContext.logger().info("Function '{}' invoked, status {}", rFunctionId, response.get__httpStatusCode__());
            return output;
        }
    }

    private byte[] requestBody(RunContext runContext) throws Exception {
        Object rendered = runContext.render(this.body).as(Object.class).orElse(null);
        if (rendered == null) {
            return null;
        }

        String text = rendered instanceof String string ? string : JacksonMapper.ofJson().writeValueAsString(rendered);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private Output read(RunContext runContext, InvokeFunctionResponse response) throws Exception {
        Output.OutputBuilder output = Output.builder()
            .statusCode(response.get__httpStatusCode__())
            .opcRequestId(response.getOpcRequestId());

        // the response is streamed to a file first, so a big one never has to fit in memory
        File file = runContext.workingDir().createTempFile(".out").toFile();
        try (InputStream stream = response.getInputStream()) {
            if (stream != null) {
                Files.copy(stream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        long size = file.length();
        output.size(size);
        if (size > MAX_BODY_BYTES) {
            URI uri = runContext.storage().putFile(file);
            output.uri(uri);
        } else {
            output.body(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        }

        return output.build();
    }

    public enum InvokeType {
        SYNC,
        DETACHED
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "HTTP status code of the call", description = "`200` for a `SYNC` call, `202` for a `DETACHED` one.")
        private final Integer statusCode;

        @Schema(title = "Response of the function", description = "Empty for a `DETACHED` call, and absent when the response is larger than 1 MiB (see `uri`).")
        private final String body;

        @Schema(title = "URI of the stored response", description = "Only set when the response is larger than 1 MiB.")
        private final URI uri;

        @Schema(title = "Size of the response in bytes")
        private final Long size;

        @Schema(title = "OCI request ID", description = "Give it to Oracle support to trace a call.")
        private final String opcRequestId;
    }
}

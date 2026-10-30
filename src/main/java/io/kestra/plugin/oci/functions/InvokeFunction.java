package io.kestra.plugin.oci.functions;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
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

import java.net.URI;

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
        Object rBody = runContext.render(this.body).as(Object.class).orElse(null);

        FunctionInvoker.Result result = FunctionInvoker.invoke(
            runContext, OciAuth.provider(runContext, this), rFunctionId, rBody, rInvokeType, this::client, this::invokeClient
        );

        return Output.builder()
            .statusCode(result.statusCode())
            .body(result.body())
            .uri(result.uri())
            .size(result.size())
            .opcRequestId(result.opcRequestId())
            .build();
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

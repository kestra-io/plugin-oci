package io.kestra.plugin.oci.functions;

import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.requests.DeleteFunctionRequest;
import com.oracle.bmc.functions.responses.DeleteFunctionResponse;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete an OCI function",
    description = "Deletes a function by its OCID. This cannot be undone. OCI answers as soon as the deletion is accepted, and the function then moves through the `DELETING` state."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete a function",
            full = true,
            code = """
                id: oci_delete_function
                namespace: company.team

                tasks:
                  - id: delete_function
                    type: io.kestra.plugin.oci.functions.DeleteFunction
                    authType: CONFIG_FILE
                    functionId: ocid1.fnfunc.oc1.eu-frankfurt-1.aaaaaaaaexample
                """
        )
    }
)
public class DeleteFunction extends AbstractFunctionsTask implements RunnableTask<DeleteFunction.Output> {
    @Schema(title = "OCID of the function", description = "For example `ocid1.fnfunc.oc1.eu-frankfurt-1.aaaa...`.")
    @NotNull
    private Property<String> functionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rFunctionId = runContext.render(this.functionId).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'functionId' is required"));

        try (FunctionsManagement client = client(OciAuth.provider(runContext, this))) {
            DeleteFunctionResponse response = client.deleteFunction(DeleteFunctionRequest.builder().functionId(rFunctionId).build());

            runContext.logger().info("Deletion of function '{}' accepted", rFunctionId);

            return Output.builder()
                .functionId(rFunctionId)
                .opcRequestId(response.getOpcRequestId())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "OCID of the deleted function")
        private final String functionId;

        @Schema(title = "OCI request ID", description = "Give it to Oracle support to trace a call.")
        private final String opcRequestId;
    }
}

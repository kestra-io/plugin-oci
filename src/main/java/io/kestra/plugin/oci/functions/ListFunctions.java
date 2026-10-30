package io.kestra.plugin.oci.functions;

import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.FunctionsManagementPaginators;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.model.FunctionSummary;
import com.oracle.bmc.functions.requests.ListFunctionsRequest;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List OCI functions",
    description = "Lists the functions of an OCI Functions application, following the pagination of the API. The function `config` is not returned, as it often holds secrets."
)
@Plugin(
    examples = {
        @Example(
            title = "List the active functions of an application",
            full = true,
            code = """
                id: oci_list_functions
                namespace: company.team

                tasks:
                  - id: list_functions
                    type: io.kestra.plugin.oci.functions.ListFunctions
                    authType: CONFIG_FILE
                    applicationId: ocid1.fnapp.oc1.eu-frankfurt-1.aaaaaaaaexample
                    lifecycleState: ACTIVE
                """
        )
    }
)
public class ListFunctions extends AbstractFunctionsTask implements RunnableTask<ListFunctions.Output> {
    private static final int PAGE_SIZE = 50;

    @Schema(title = "OCID of the application", description = "Only the functions of this application are listed.")
    @NotNull
    private Property<String> applicationId;

    @Schema(title = "Display name filter", description = "Only return functions with exactly this display name.")
    private Property<String> displayName;

    @Schema(title = "Lifecycle state filter", description = "Only return functions in this state, for example `ACTIVE`, `INACTIVE`, `CREATING`, `UPDATING`, `DELETING`, `DELETED` or `FAILED`.")
    private Property<String> lifecycleState;

    @Schema(title = "Maximum number of functions", description = "Stops listing once this many functions were returned. By default all the functions are returned.")
    private Property<Integer> limit;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String rApplicationId = runContext.render(this.applicationId).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'applicationId' is required"));
        Integer rLimit = runContext.render(this.limit).as(Integer.class).orElse(null);
        if (rLimit != null && rLimit < 1) {
            throw new IllegalArgumentException("'limit' must be at least 1, got " + rLimit);
        }

        ListFunctionsRequest.Builder request = ListFunctionsRequest.builder()
            .applicationId(rApplicationId)
            .limit(PAGE_SIZE);
        runContext.render(this.displayName).as(String.class).filter(value -> !value.isBlank()).ifPresent(request::displayName);
        runContext.render(this.lifecycleState).as(String.class).filter(value -> !value.isBlank())
            .ifPresent(value -> request.lifecycleState(lifecycleState(value)));

        List<Item> functions = new ArrayList<>();
        try (FunctionsManagement client = client(OciAuth.provider(runContext, this))) {
            // the paginator follows the next-page tokens of the API
            for (FunctionSummary summary : new FunctionsManagementPaginators(client).listFunctionsRecordIterator(request.build())) {
                functions.add(Item.of(summary));
                if (rLimit != null && functions.size() >= rLimit) {
                    break;
                }
            }
        }

        runContext.logger().info("Found {} function(s)", functions.size());

        return Output.builder()
            .functions(functions)
            .count(functions.size())
            .build();
    }

    private static Function.LifecycleState lifecycleState(String value) {
        Function.LifecycleState state = Function.LifecycleState.create(value.toUpperCase());
        if (state == Function.LifecycleState.UnknownEnumValue) {
            throw new IllegalArgumentException("Unknown 'lifecycleState' '" + value + "', expected one of CREATING, ACTIVE, INACTIVE, UPDATING, DELETING, DELETED, FAILED");
        }
        return state;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The functions")
        private final List<Item> functions;

        @Schema(title = "Number of functions returned")
        private final Integer count;
    }

    @Builder
    @Getter
    public static class Item {
        @Schema(title = "OCID of the function")
        private final String id;

        @Schema(title = "Display name")
        private final String displayName;

        @Schema(title = "OCID of the application the function belongs to")
        private final String applicationId;

        @Schema(title = "OCID of the compartment")
        private final String compartmentId;

        @Schema(title = "Lifecycle state", description = "For example `ACTIVE`.")
        private final String lifecycleState;

        @Schema(title = "Container image of the function")
        private final String image;

        @Schema(title = "Memory in MB")
        private final Long memoryInMBs;

        @Schema(title = "Timeout in seconds")
        private final Integer timeoutInSeconds;

        @Schema(title = "Invoke endpoint", description = "The base URL used to invoke this function.")
        private final String invokeEndpoint;

        @Schema(title = "Creation time")
        private final Instant timeCreated;

        @Schema(title = "Last update time")
        private final Instant timeUpdated;

        @Schema(title = "Free-form tags")
        private final Map<String, String> freeformTags;

        static Item of(FunctionSummary summary) {
            return Item.builder()
                .id(summary.getId())
                .displayName(summary.getDisplayName())
                .applicationId(summary.getApplicationId())
                .compartmentId(summary.getCompartmentId())
                .lifecycleState(summary.getLifecycleState() == null ? null : summary.getLifecycleState().getValue())
                .image(summary.getImage())
                .memoryInMBs(summary.getMemoryInMBs())
                .timeoutInSeconds(summary.getTimeoutInSeconds())
                .invokeEndpoint(summary.getInvokeEndpoint())
                .timeCreated(summary.getTimeCreated() == null ? null : summary.getTimeCreated().toInstant())
                .timeUpdated(summary.getTimeUpdated() == null ? null : summary.getTimeUpdated().toInstant())
                .freeformTags(summary.getFreeformTags())
                .build();
        }
    }
}

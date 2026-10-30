package io.kestra.plugin.oci.functions;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Runs the tasks against a real OCI tenancy. It is skipped, and the build still passes, unless these environment variables are set:
 * <ul>
 *     <li>{@code OCI_TENANCY_OCID}, {@code OCI_USER_OCID}, {@code OCI_FINGERPRINT}, {@code OCI_PRIVATE_KEY}, {@code OCI_REGION}: an API signing key;</li>
 *     <li>{@code OCI_FUNCTION_OCID}: an {@code ACTIVE} function, ideally one that echoes its input;</li>
 *     <li>{@code OCI_APP_OCID}: the application that function belongs to.</li>
 * </ul>
 * The create/delete test also needs {@code OCI_FUNCTION_IMAGE}, an image in the OCI Registry of that region. It creates a function with a
 * unique name in the application and deletes it at the end.
 */
@KestraTest
@EnabledIf("credentialsPresent")
class FunctionsIntegrationTest {
    @Inject
    private RunContextFactory runContextFactory;

    static boolean credentialsPresent() {
        for (String name : new String[]{"OCI_TENANCY_OCID", "OCI_USER_OCID", "OCI_FINGERPRINT", "OCI_PRIVATE_KEY", "OCI_REGION", "OCI_FUNCTION_OCID", "OCI_APP_OCID"}) {
            if (env(name) == null) {
                return false;
            }
        }
        return true;
    }

    @Test
    void getFunction() throws Exception {
        GetFunction.Output output = GetFunction.builder()
            .id("get").type(GetFunction.class.getName())
            .region(Property.ofValue(env("OCI_REGION")))
            .tenancyOcid(Property.ofValue(env("OCI_TENANCY_OCID")))
            .userId(Property.ofValue(env("OCI_USER_OCID")))
            .fingerprint(Property.ofValue(env("OCI_FINGERPRINT")))
            .privateKey(Property.ofValue(env("OCI_PRIVATE_KEY")))
            .functionId(Property.ofValue(env("OCI_FUNCTION_OCID")))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getId(), is(env("OCI_FUNCTION_OCID")));
        assertThat(output.getApplicationId(), is(env("OCI_APP_OCID")));
        assertThat(output.getInvokeEndpoint(), startsWith("https://"));
        assertThat(output.getLifecycleState(), is("ACTIVE"));
    }

    @Test
    void listFunctions() throws Exception {
        ListFunctions.Output output = ListFunctions.builder()
            .id("list").type(ListFunctions.class.getName())
            .region(Property.ofValue(env("OCI_REGION")))
            .tenancyOcid(Property.ofValue(env("OCI_TENANCY_OCID")))
            .userId(Property.ofValue(env("OCI_USER_OCID")))
            .fingerprint(Property.ofValue(env("OCI_FINGERPRINT")))
            .privateKey(Property.ofValue(env("OCI_PRIVATE_KEY")))
            .applicationId(Property.ofValue(env("OCI_APP_OCID")))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getCount(), greaterThanOrEqualTo(1));
        assertThat(output.getFunctions().stream().map(ListFunctions.Item::getId).toList(), hasItem(env("OCI_FUNCTION_OCID")));
    }

    @Test
    void invokeFunction() throws Exception {
        InvokeFunction.Output output = InvokeFunction.builder()
            .id("invoke").type(InvokeFunction.class.getName())
            .region(Property.ofValue(env("OCI_REGION")))
            .tenancyOcid(Property.ofValue(env("OCI_TENANCY_OCID")))
            .userId(Property.ofValue(env("OCI_USER_OCID")))
            .fingerprint(Property.ofValue(env("OCI_FINGERPRINT")))
            .privateKey(Property.ofValue(env("OCI_PRIVATE_KEY")))
            .functionId(Property.ofValue(env("OCI_FUNCTION_OCID")))
            .body(Property.ofValue(Map.of("name", "Kestra")))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getStatusCode(), is(200));
        assertThat(output.getBody(), notNullValue());
        assertThat(output.getOpcRequestId(), notNullValue());
    }

    @Test
    void trigger() throws Exception {
        FunctionInvocationTrigger trigger = FunctionInvocationTrigger.builder()
            .id("poll").type(FunctionInvocationTrigger.class.getName())
            .interval(Duration.ofHours(1))
            .region(Property.ofValue(env("OCI_REGION")))
            .tenancyOcid(Property.ofValue(env("OCI_TENANCY_OCID")))
            .userId(Property.ofValue(env("OCI_USER_OCID")))
            .fingerprint(Property.ofValue(env("OCI_FINGERPRINT")))
            .privateKey(Property.ofValue(env("OCI_PRIVATE_KEY")))
            .functionId(Property.ofValue(env("OCI_FUNCTION_OCID")))
            .body(Property.ofValue(Map.of()))
            .build();
        var mock = TestsUtils.mockTrigger(runContextFactory, trigger);

        Optional<Execution> execution = trigger.evaluate(mock.getKey(), mock.getValue());

        assertThat(execution.isPresent(), is(true));
        assertThat(execution.get().getTrigger().getVariables().get("statusCode"), is(200));
    }

    /**
     * Creates a function, reads it back, then deletes it. Needs {@code OCI_FUNCTION_IMAGE}.
     */
    @Test
    @EnabledIf("imagePresent")
    void createThenDelete() throws Exception {
        String name = "kestra-it-" + UUID.randomUUID().toString().substring(0, 8);

        CreateFunction.Output created = CreateFunction.builder()
            .id("create").type(CreateFunction.class.getName())
            .region(Property.ofValue(env("OCI_REGION")))
            .tenancyOcid(Property.ofValue(env("OCI_TENANCY_OCID")))
            .userId(Property.ofValue(env("OCI_USER_OCID")))
            .fingerprint(Property.ofValue(env("OCI_FINGERPRINT")))
            .privateKey(Property.ofValue(env("OCI_PRIVATE_KEY")))
            .applicationId(Property.ofValue(env("OCI_APP_OCID")))
            .displayName(Property.ofValue(name))
            .image(Property.ofValue(env("OCI_FUNCTION_IMAGE")))
            .memoryInMBs(Property.ofValue(128L))
            .timeoutInSeconds(Property.ofValue(30))
            .build()
            .run(runContextFactory.of());

        try {
            assertThat(created.getDisplayName(), is(name));
            assertThat(created.getId(), containsString("fnfunc"));
        } finally {
            DeleteFunction.Output deleted = DeleteFunction.builder()
                .id("delete").type(DeleteFunction.class.getName())
                .region(Property.ofValue(env("OCI_REGION")))
                .tenancyOcid(Property.ofValue(env("OCI_TENANCY_OCID")))
                .userId(Property.ofValue(env("OCI_USER_OCID")))
                .fingerprint(Property.ofValue(env("OCI_FINGERPRINT")))
                .privateKey(Property.ofValue(env("OCI_PRIVATE_KEY")))
                .functionId(Property.ofValue(created.getId()))
                .build()
                .run(runContextFactory.of());

            assertThat(deleted.getFunctionId(), is(created.getId()));
        }
    }

    static boolean imagePresent() {
        return env("OCI_FUNCTION_IMAGE") != null;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}

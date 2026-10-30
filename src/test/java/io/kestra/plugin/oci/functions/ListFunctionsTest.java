package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.model.FunctionSummary;
import com.oracle.bmc.functions.requests.ListFunctionsRequest;
import com.oracle.bmc.functions.responses.ListFunctionsResponse;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListFunctionsTest {
    private static final String APPLICATION_ID = "ocid1.fnapp.oc1.eu-frankfurt-1.aaaa";

    private static String privateKey;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        privateKey = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void followsThePagination() throws Exception {
        TestListFunctions task = task().build();

        ListFunctions.Output output = task.run(runContextFactory.of());

        assertThat(output.getCount(), is(3));
        assertThat(output.getFunctions().get(0).getDisplayName(), is("fn-1"));
        assertThat(output.getFunctions().get(2).getDisplayName(), is("fn-3"));
        assertThat(output.getFunctions().get(0).getLifecycleState(), is("ACTIVE"));
        assertThat(output.getFunctions().get(0).getTimeCreated(), is(new Date(1_700_000_000_000L).toInstant()));
        // two pages were requested, the second one with the token of the first
        assertThat(task.requests.size(), is(2));
        assertThat(task.requests.get(1).getPage(), is("page-2"));
        assertThat(task.requests.get(0).getApplicationId(), is(APPLICATION_ID));
    }

    @Test
    void filters() throws Exception {
        TestListFunctions task = task()
            .displayName(Property.ofValue("fn-1"))
            .lifecycleState(Property.ofValue("active"))
            .build();

        task.run(runContextFactory.of());

        assertThat(task.requests.get(0).getDisplayName(), is("fn-1"));
        assertThat(task.requests.get(0).getLifecycleState(), is(Function.LifecycleState.Active));
    }

    @Test
    void limitStopsEarly() throws Exception {
        TestListFunctions task = task().limit(Property.ofValue(1)).build();

        ListFunctions.Output output = task.run(runContextFactory.of());

        assertThat(output.getCount(), is(1));
        assertThat(task.requests.size(), is(1));
    }

    @Test
    void unknownLifecycleStateIsRejected() {
        TestListFunctions task = task().lifecycleState(Property.ofValue("nope")).build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("'lifecycleState'"));
    }

    @Test
    void applicationIdIsRequired() {
        TestListFunctions task = task().applicationId(Property.ofValue(" ")).build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("'applicationId'"));
    }

    @Test
    void invalidLimitIsRejected() {
        TestListFunctions task = task().limit(Property.ofValue(0)).build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
    }

    private static TestListFunctions.TestListFunctionsBuilder<?, ?> task() {
        return TestListFunctions.builder()
            .id("test")
            .type(TestListFunctions.class.getName())
            .region(Property.ofValue("eu-frankfurt-1"))
            .tenancyOcid(Property.ofValue("ocid1.tenancy.oc1..t"))
            .userId(Property.ofValue("ocid1.user.oc1..u"))
            .fingerprint(Property.ofValue("aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"))
            .privateKey(Property.ofValue(privateKey))
            .applicationId(Property.ofValue(APPLICATION_ID));
    }

    private static FunctionSummary summary(String name) {
        return FunctionSummary.builder()
            .id("ocid1.fnfunc.oc1..".concat(name))
            .displayName(name)
            .lifecycleState(Function.LifecycleState.Active)
            .timeCreated(new Date(1_700_000_000_000L))
            .build();
    }

    // public with a no-arg constructor: Kestra's annotation processor registers every Task subclass as a plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class TestListFunctions extends ListFunctions {
        final List<ListFunctionsRequest> requests = new ArrayList<>();

        @Override
        protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
            // FunctionsManagement has dozens of methods, only listFunctions and close are used here
            return (FunctionsManagement) Proxy.newProxyInstance(
                FunctionsManagement.class.getClassLoader(),
                new Class<?>[]{FunctionsManagement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    if (method.getName().equals("listFunctions")) {
                        ListFunctionsRequest request = (ListFunctionsRequest) args[0];
                        requests.add(request);

                        // two pages: fn-1 and fn-2, then fn-3
                        return request.getPage() == null
                            ? ListFunctionsResponse.builder().items(List.of(summary("fn-1"), summary("fn-2"))).opcNextPage("page-2").build()
                            : ListFunctionsResponse.builder().items(List.of(summary("fn-3"))).build();
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }
}

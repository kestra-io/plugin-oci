package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.GetFunctionRequest;
import com.oracle.bmc.functions.responses.GetFunctionResponse;
import com.oracle.bmc.model.BmcException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class GetFunctionTest {
    private static final String FUNCTION_ID = "ocid1.fnfunc.oc1.eu-frankfurt-1.aaaa";

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
    void run() throws Exception {
        Date created = new Date(1_700_000_000_000L);
        Function function = Function.builder()
            .id(FUNCTION_ID)
            .displayName("hello")
            .applicationId("ocid1.fnapp.oc1..app")
            .compartmentId("ocid1.compartment.oc1..c")
            .lifecycleState(Function.LifecycleState.Active)
            .image("fra.ocir.io/ns/hello:0.0.1")
            .memoryInMBs(256L)
            .timeoutInSeconds(30)
            .invokeEndpoint("https://abc.eu-frankfurt-1.functions.oci.oraclecloud.com")
            .timeCreated(created)
            .freeformTags(Map.of("team", "data"))
            .config(Map.of("SECRET", "must-not-be-returned"))
            .build();
        TestGetFunction task = task(request -> GetFunctionResponse.builder().function(function).build());

        GetFunction.Output output = task.run(runContextFactory.of());

        assertThat(task.requestedFunctionId.get(), is(FUNCTION_ID));
        assertThat(output.getId(), is(FUNCTION_ID));
        assertThat(output.getDisplayName(), is("hello"));
        assertThat(output.getLifecycleState(), is("ACTIVE"));
        assertThat(output.getMemoryInMBs(), is(256L));
        assertThat(output.getTimeoutInSeconds(), is(30));
        assertThat(output.getInvokeEndpoint(), is("https://abc.eu-frankfurt-1.functions.oci.oraclecloud.com"));
        assertThat(output.getTimeCreated(), is(created.toInstant()));
        assertThat(output.getTimeUpdated() == null, is(true));
        assertThat(output.getFreeformTags(), is(Map.of("team", "data")));
        assertThat(output.toString(), org.hamcrest.Matchers.not(containsString("must-not-be-returned")));
    }

    @Test
    void usesTheSharedAuth() throws Exception {
        TestGetFunction task = task(request -> GetFunctionResponse.builder().function(Function.builder().id(FUNCTION_ID).build()).build());

        task.run(runContextFactory.of());

        assertThat(task.provider.get() instanceof SimpleAuthenticationDetailsProvider, is(true));
    }

    @Test
    void functionIdIsRequired() {
        TestGetFunction task = TestGetFunction.builder()
            .id("test")
            .type(TestGetFunction.class.getName())
            .functionId(Property.ofValue(" "))
            .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("'functionId'"));
    }

    @Test
    void ociErrorIsNotSwallowed() {
        TestGetFunction task = task(request -> {
            throw new BmcException(404, "NotAuthorizedOrNotFound", "Function not found", "opc-1");
        });

        BmcException exception = assertThrows(BmcException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getStatusCode(), is(404));
    }

    private static TestGetFunction task(java.util.function.Function<GetFunctionRequest, GetFunctionResponse> answer) {
        return TestGetFunction.builder()
            .id("test")
            .type(TestGetFunction.class.getName())
            .region(Property.ofValue("eu-frankfurt-1"))
            .tenancyOcid(Property.ofValue("ocid1.tenancy.oc1..t"))
            .userId(Property.ofValue("ocid1.user.oc1..u"))
            .fingerprint(Property.ofValue("aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"))
            .privateKey(Property.ofValue(privateKey))
            .functionId(Property.ofValue(FUNCTION_ID))
            .answer(answer)
            .build();
    }

    // public with a no-arg constructor: Kestra's annotation processor registers every Task subclass as a plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class TestGetFunction extends GetFunction {
        private java.util.function.Function<GetFunctionRequest, GetFunctionResponse> answer;
        final AtomicReference<String> requestedFunctionId = new AtomicReference<>();
        final AtomicReference<BasicAuthenticationDetailsProvider> provider = new AtomicReference<>();

        @Override
        protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
            this.provider.set(provider);

            // FunctionsManagement has dozens of methods, only getFunction and close are used here
            return (FunctionsManagement) Proxy.newProxyInstance(
                FunctionsManagement.class.getClassLoader(),
                new Class<?>[]{FunctionsManagement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    if (method.getName().equals("getFunction")) {
                        GetFunctionRequest request = (GetFunctionRequest) args[0];
                        requestedFunctionId.set(request.getFunctionId());
                        try {
                            return answer.apply(request);
                        } catch (RuntimeException e) {
                            throw new InvocationTargetException(e).getTargetException();
                        }
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }
}

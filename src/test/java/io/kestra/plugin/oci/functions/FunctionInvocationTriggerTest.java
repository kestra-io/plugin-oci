package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsInvoke;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.InvokeFunctionRequest;
import com.oracle.bmc.functions.responses.GetFunctionResponse;
import com.oracle.bmc.functions.responses.InvokeFunctionResponse;
import com.oracle.bmc.model.BmcException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class FunctionInvocationTriggerTest {
    private static final String FUNCTION_ID = "ocid1.fnfunc.oc1.eu-frankfurt-1.aaaa";
    private static final String ENDPOINT = "https://abc.eu-frankfurt-1.functions.oci.oraclecloud.com";

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
    void createsAnExecutionWithTheResponse() throws Exception {
        TestTrigger trigger = trigger().body(Property.ofValue(Map.of("source", "kestra"))).build();
        var mock = TestsUtils.mockTrigger(runContextFactory, trigger);

        Optional<Execution> execution = trigger.evaluate(mock.getKey(), mock.getValue());

        assertThat(execution.isPresent(), is(true));
        assertThat(execution.get().getTrigger().getVariables().get("body"), is("{\"ok\":true}"));
        assertThat(execution.get().getTrigger().getVariables().get("statusCode"), is(200));
        // the body is sent as JSON, to the endpoint of the function, with the shared auth
        assertThat(new String(trigger.request.get().getInvokeFunctionBody().readAllBytes(), StandardCharsets.UTF_8), is("{\"source\":\"kestra\"}"));
        assertThat(trigger.endpoint.get(), is(ENDPOINT));
        assertThat(trigger.provider.get() instanceof SimpleAuthenticationDetailsProvider, is(true));
    }

    @Test
    void failingCallCreatesNoExecution() {
        TestTrigger trigger = trigger().failure(new BmcException(502, "FunctionInvokeError", "function crashed", "opc-2")).build();
        var mock = TestsUtils.mockTrigger(runContextFactory, trigger);

        BmcException exception = assertThrows(BmcException.class, () -> trigger.evaluate(mock.getKey(), mock.getValue()));

        assertThat(exception.getStatusCode(), is(502));
    }

    @Test
    void functionIdIsRequired() {
        TestTrigger trigger = trigger().functionId(Property.ofValue(" ")).build();
        var mock = TestsUtils.mockTrigger(runContextFactory, trigger);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(mock.getKey(), mock.getValue()));

        assertThat(exception.getMessage(), containsString("'functionId'"));
    }

    @Test
    void secretsAreNotPrintable() {
        TestTrigger trigger = trigger().passphrase(Property.ofValue("s3cret")).build();

        assertThat(trigger.toString().contains("BEGIN PRIVATE KEY"), is(false));
        assertThat(trigger.toString().contains("s3cret"), is(false));
    }

    private static TestTrigger.TestTriggerBuilder<?, ?> trigger() {
        return TestTrigger.builder()
            .id("poll")
            .type(TestTrigger.class.getName())
            .interval(Duration.ofMinutes(1))
            .region(Property.ofValue("eu-frankfurt-1"))
            .tenancyOcid(Property.ofValue("ocid1.tenancy.oc1..t"))
            .userId(Property.ofValue("ocid1.user.oc1..u"))
            .fingerprint(Property.ofValue("aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"))
            .privateKey(Property.ofValue(privateKey))
            .functionId(Property.ofValue(FUNCTION_ID));
    }

    // public with a no-arg constructor: Kestra's annotation processor registers every trigger subclass as a plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class TestTrigger extends FunctionInvocationTrigger {
        private RuntimeException failure;
        final AtomicReference<String> endpoint = new AtomicReference<>();
        final AtomicReference<InvokeFunctionRequest> request = new AtomicReference<>();
        final AtomicReference<BasicAuthenticationDetailsProvider> provider = new AtomicReference<>();

        @Override
        protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
            this.provider.set(provider);

            return (FunctionsManagement) Proxy.newProxyInstance(
                FunctionsManagement.class.getClassLoader(),
                new Class<?>[]{FunctionsManagement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "getFunction" -> GetFunctionResponse.builder()
                        .function(Function.builder().id(FUNCTION_ID).lifecycleState(Function.LifecycleState.Active).invokeEndpoint(ENDPOINT).build())
                        .build();
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            );
        }

        @Override
        protected FunctionsInvoke invokeClient(BasicAuthenticationDetailsProvider provider, String invokeEndpoint) {
            endpoint.set(invokeEndpoint);

            return (FunctionsInvoke) Proxy.newProxyInstance(
                FunctionsInvoke.class.getClassLoader(),
                new Class<?>[]{FunctionsInvoke.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "invokeFunction" -> {
                        request.set((InvokeFunctionRequest) args[0]);
                        if (failure != null) {
                            throw failure;
                        }
                        yield InvokeFunctionResponse.builder()
                            .__httpStatusCode__(200)
                            .opcRequestId("opc-1")
                            .inputStream(new ByteArrayInputStream("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)))
                            .build();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }
}

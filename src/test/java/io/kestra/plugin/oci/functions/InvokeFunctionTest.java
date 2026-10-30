package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsInvoke;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.InvokeFunctionRequest;
import com.oracle.bmc.functions.responses.GetFunctionResponse;
import com.oracle.bmc.functions.responses.InvokeFunctionResponse;
import com.oracle.bmc.model.BmcException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class InvokeFunctionTest {
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
    void invokesTheEndpointOfTheFunction() throws Exception {
        TestInvokeFunction task = task().body(Property.ofValue("hello")).response("world".getBytes(StandardCharsets.UTF_8)).build();

        InvokeFunction.Output output = task.run(runContextFactory.of());

        assertThat(task.endpoint.get(), is(ENDPOINT));
        assertThat(task.request.get().getFunctionId(), is(FUNCTION_ID));
        assertThat(task.request.get().getFnInvokeType(), is(InvokeFunctionRequest.FnInvokeType.Sync));
        assertThat(new String(task.request.get().getInvokeFunctionBody().readAllBytes(), StandardCharsets.UTF_8), is("hello"));
        assertThat(output.getBody(), is("world"));
        assertThat(output.getStatusCode(), is(200));
        assertThat(output.getSize(), is(5L));
        assertThat(output.getOpcRequestId(), is("opc-1"));
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void objectBodyIsSentAsJson() throws Exception {
        TestInvokeFunction task = task().body(Property.ofValue(Map.of("name", "Kestra"))).build();

        task.run(runContextFactory.of());

        assertThat(new String(task.request.get().getInvokeFunctionBody().readAllBytes(), StandardCharsets.UTF_8), is("{\"name\":\"Kestra\"}"));
    }

    @Test
    void noBodyMeansNoInput() throws Exception {
        TestInvokeFunction task = task().build();

        task.run(runContextFactory.of());

        assertThat(task.request.get().getInvokeFunctionBody(), nullValue());
    }

    @Test
    void detached() throws Exception {
        TestInvokeFunction task = task().invokeType(Property.ofValue(InvokeFunction.InvokeType.DETACHED)).response(new byte[0]).status(202).build();

        InvokeFunction.Output output = task.run(runContextFactory.of());

        assertThat(task.request.get().getFnInvokeType(), is(InvokeFunctionRequest.FnInvokeType.Detached));
        assertThat(output.getStatusCode(), is(202));
        assertThat(output.getBody(), is(""));
    }

    @Test
    void bigResponseGoesToTheInternalStorage() throws Exception {
        byte[] big = new byte[1024 * 1024 + 1];
        Arrays.fill(big, (byte) 'a');
        RunContext runContext = runContextFactory.of();
        TestInvokeFunction task = task().response(big).build();

        InvokeFunction.Output output = task.run(runContext);

        assertThat(output.getBody(), nullValue());
        assertThat(output.getSize(), is((long) big.length));
        assertThat(runContext.storage().getFile(output.getUri()).readAllBytes().length, is(big.length));
    }

    @Test
    void functionWithoutEndpointFails() {
        TestInvokeFunction task = task().endpointOfFunction(null).build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString(FUNCTION_ID));
    }

    @Test
    void functionIdIsRequired() {
        TestInvokeFunction task = task().functionId(Property.ofValue(" ")).build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("'functionId'"));
    }

    @Test
    void ociErrorIsNotSwallowed() {
        TestInvokeFunction task = task().failure(new BmcException(502, "FunctionInvokeError", "function crashed", "opc-2")).build();

        BmcException exception = assertThrows(BmcException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getStatusCode(), is(502));
    }

    private static TestInvokeFunction.TestInvokeFunctionBuilder<?, ?> task() {
        return TestInvokeFunction.builder()
            .id("test")
            .type(TestInvokeFunction.class.getName())
            .region(Property.ofValue("eu-frankfurt-1"))
            .tenancyOcid(Property.ofValue("ocid1.tenancy.oc1..t"))
            .userId(Property.ofValue("ocid1.user.oc1..u"))
            .fingerprint(Property.ofValue("aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"))
            .privateKey(Property.ofValue(privateKey))
            .functionId(Property.ofValue(FUNCTION_ID))
            .endpointOfFunction(ENDPOINT)
            .response("ok".getBytes(StandardCharsets.UTF_8))
            .status(200);
    }

    // public with a no-arg constructor: Kestra's annotation processor registers every Task subclass as a plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class TestInvokeFunction extends InvokeFunction {
        private String endpointOfFunction;
        private byte[] response;
        private int status;
        private RuntimeException failure;
        final AtomicReference<String> endpoint = new AtomicReference<>();
        final AtomicReference<InvokeFunctionRequest> request = new AtomicReference<>();

        @Override
        protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
            return (FunctionsManagement) Proxy.newProxyInstance(
                FunctionsManagement.class.getClassLoader(),
                new Class<?>[]{FunctionsManagement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "getFunction" -> GetFunctionResponse.builder()
                        .function(Function.builder().id(FUNCTION_ID).lifecycleState(Function.LifecycleState.Active).invokeEndpoint(endpointOfFunction).build())
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
                            .__httpStatusCode__(status)
                            .opcRequestId("opc-1")
                            .inputStream(new ByteArrayInputStream(response))
                            .build();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }
}

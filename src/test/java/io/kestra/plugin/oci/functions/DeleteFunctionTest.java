package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.requests.DeleteFunctionRequest;
import com.oracle.bmc.functions.responses.DeleteFunctionResponse;
import com.oracle.bmc.model.BmcException;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class DeleteFunctionTest {
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
        TestDeleteFunction task = task().build();

        DeleteFunction.Output output = task.run(runContextFactory.of());

        assertThat(task.requestedFunctionId.get(), is(FUNCTION_ID));
        assertThat(output.getFunctionId(), is(FUNCTION_ID));
        assertThat(output.getOpcRequestId(), is("opc-1"));
    }

    @Test
    void functionIdIsRequired() {
        TestDeleteFunction task = task().functionId(Property.ofValue(" ")).build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("'functionId'"));
        assertThat(task.requestedFunctionId.get() == null, is(true));
    }

    @Test
    void ociErrorIsNotSwallowed() {
        TestDeleteFunction task = task().failure(new BmcException(404, "NotAuthorizedOrNotFound", "not found", "opc-2")).build();

        BmcException exception = assertThrows(BmcException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getStatusCode(), is(404));
    }

    private static TestDeleteFunction.TestDeleteFunctionBuilder<?, ?> task() {
        return TestDeleteFunction.builder()
            .id("test")
            .type(TestDeleteFunction.class.getName())
            .region(Property.ofValue("eu-frankfurt-1"))
            .tenancyOcid(Property.ofValue("ocid1.tenancy.oc1..t"))
            .userId(Property.ofValue("ocid1.user.oc1..u"))
            .fingerprint(Property.ofValue("aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"))
            .privateKey(Property.ofValue(privateKey))
            .functionId(Property.ofValue(FUNCTION_ID));
    }

    // public with a no-arg constructor: Kestra's annotation processor registers every Task subclass as a plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class TestDeleteFunction extends DeleteFunction {
        private RuntimeException failure;
        final AtomicReference<String> requestedFunctionId = new AtomicReference<>();

        @Override
        protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
            return (FunctionsManagement) Proxy.newProxyInstance(
                FunctionsManagement.class.getClassLoader(),
                new Class<?>[]{FunctionsManagement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "deleteFunction" -> {
                        requestedFunctionId.set(((DeleteFunctionRequest) args[0]).getFunctionId());
                        if (failure != null) {
                            throw failure;
                        }
                        yield DeleteFunctionResponse.builder().opcRequestId("opc-1").build();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }
}

package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.model.CreateFunctionDetails;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.CreateFunctionRequest;
import com.oracle.bmc.functions.responses.CreateFunctionResponse;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class CreateFunctionTest {
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
        TestCreateFunction task = task()
            .imageDigest(Property.ofValue("sha256:abc"))
            .memoryInMBs(Property.ofValue(256L))
            .timeoutInSeconds(Property.ofValue(30))
            .config(Property.ofValue(Map.of("GREETING", "Hello")))
            .freeformTags(Property.ofValue(Map.of("team", "data")))
            .build();

        CreateFunction.Output output = task.run(runContextFactory.of());

        CreateFunctionDetails details = task.details.get();
        assertThat(details.getApplicationId(), is("ocid1.fnapp.oc1..app"));
        assertThat(details.getDisplayName(), is("hello"));
        assertThat(details.getImage(), is("fra.ocir.io/ns/hello:0.0.1"));
        assertThat(details.getImageDigest(), is("sha256:abc"));
        assertThat(details.getMemoryInMBs(), is(256L));
        assertThat(details.getTimeoutInSeconds(), is(30));
        assertThat(details.getConfig(), is(Map.of("GREETING", "Hello")));
        assertThat(details.getFreeformTags(), is(Map.of("team", "data")));
        assertThat(output.getId(), is("ocid1.fnfunc.oc1..new"));
        assertThat(output.getLifecycleState(), is("CREATING"));
        assertThat(output.getInvokeEndpoint(), is("https://abc.functions.oci.oraclecloud.com"));
        assertThat(output.getOpcRequestId(), is("opc-1"));
    }

    @Test
    void optionalPropertiesAreLeftToOci() throws Exception {
        TestCreateFunction task = task().build();

        task.run(runContextFactory.of());

        CreateFunctionDetails details = task.details.get();
        assertThat(details.getMemoryInMBs(), nullValue());
        assertThat(details.getTimeoutInSeconds(), nullValue());
        assertThat(details.getConfig(), nullValue());
    }

    @Test
    void requiredPropertiesAreNamed() {
        for (String name : new String[]{"applicationId", "displayName", "image"}) {
            TestCreateFunction.TestCreateFunctionBuilder<?, ?> builder = task();
            switch (name) {
                case "applicationId" -> builder.applicationId(Property.ofValue(" "));
                case "displayName" -> builder.displayName(null);
                default -> builder.image(Property.ofValue(""));
            }
            TestCreateFunction task = builder.build();

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

            assertThat(exception.getMessage(), containsString("'" + name + "'"));
        }
    }

    @Test
    void ociErrorIsNotSwallowed() {
        TestCreateFunction task = task().failure(new BmcException(409, "Conflict", "name already used", "opc-2")).build();

        BmcException exception = assertThrows(BmcException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getStatusCode(), is(409));
    }

    private static TestCreateFunction.TestCreateFunctionBuilder<?, ?> task() {
        return TestCreateFunction.builder()
            .id("test")
            .type(TestCreateFunction.class.getName())
            .region(Property.ofValue("eu-frankfurt-1"))
            .tenancyOcid(Property.ofValue("ocid1.tenancy.oc1..t"))
            .userId(Property.ofValue("ocid1.user.oc1..u"))
            .fingerprint(Property.ofValue("aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"))
            .privateKey(Property.ofValue(privateKey))
            .applicationId(Property.ofValue("ocid1.fnapp.oc1..app"))
            .displayName(Property.ofValue("hello"))
            .image(Property.ofValue("fra.ocir.io/ns/hello:0.0.1"));
    }

    // public with a no-arg constructor: Kestra's annotation processor registers every Task subclass as a plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class TestCreateFunction extends CreateFunction {
        private RuntimeException failure;
        final AtomicReference<CreateFunctionDetails> details = new AtomicReference<>();

        @Override
        protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
            return (FunctionsManagement) Proxy.newProxyInstance(
                FunctionsManagement.class.getClassLoader(),
                new Class<?>[]{FunctionsManagement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "createFunction" -> {
                        details.set(((CreateFunctionRequest) args[0]).getCreateFunctionDetails());
                        if (failure != null) {
                            throw failure;
                        }
                        yield CreateFunctionResponse.builder()
                            .opcRequestId("opc-1")
                            .function(Function.builder()
                                .id("ocid1.fnfunc.oc1..new")
                                .displayName("hello")
                                .applicationId("ocid1.fnapp.oc1..app")
                                .lifecycleState(Function.LifecycleState.Creating)
                                .invokeEndpoint("https://abc.functions.oci.oraclecloud.com")
                                .build())
                            .build();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }
}

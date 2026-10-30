package io.kestra.plugin.oci;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.RegionProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
public class OciAuthTest {
    private static final String TENANCY = "ocid1.tenancy.oc1..tenancy";
    private static final String USER = "ocid1.user.oc1..user";
    private static final String FINGERPRINT = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99";

    private static String privateKey;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void apiKey() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of("region", "eu-frankfurt-1"));

        TestTask task = TestTask.builder()
            .id("test")
            .type(TestTask.class.getName())
            .region(Property.ofExpression("{{ region }}"))
            .tenancyOcid(Property.ofValue(TENANCY))
            .userId(Property.ofValue(USER))
            .fingerprint(Property.ofValue(FINGERPRINT))
            .privateKey(Property.ofValue(privateKey))
            .build();

        BasicAuthenticationDetailsProvider provider = OciAuth.provider(runContext, task);

        SimpleAuthenticationDetailsProvider simple = (SimpleAuthenticationDetailsProvider) provider;
        assertThat(simple.getTenantId(), is(TENANCY));
        assertThat(simple.getUserId(), is(USER));
        assertThat(simple.getFingerprint(), is(FINGERPRINT));
        assertThat(((RegionProvider) provider).getRegion().getRegionId(), is("eu-frankfurt-1"));
        assertThat(simple.getPassphraseCharacters() == null, is(true));
    }

    @Test
    void apiKeyWithPassphrase() throws Exception {
        TestTask task = validTask()
            .passphrase(Property.ofValue("s3cret"))
            .build();

        SimpleAuthenticationDetailsProvider provider = (SimpleAuthenticationDetailsProvider) OciAuth.provider(runContextFactory.of(), task);

        assertThat(new String(provider.getPassphraseCharacters()), is("s3cret"));
    }

    @Test
    void apiKeyIsTheDefaultAuthType() throws Exception {
        TestTask task = validTask().build();

        assertThat(task.getAuthType().toString(), containsString("API_KEY"));
        assertThat(OciAuth.provider(runContextFactory.of(), task) instanceof SimpleAuthenticationDetailsProvider, is(true));
    }

    @Test
    void apiKeyMissingFieldIsNamed() {
        TestTask task = validTask()
            .fingerprint(null)
            .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> OciAuth.provider(runContextFactory.of(), task));

        assertThat(exception.getMessage(), containsString("'fingerprint'"));
        assertThat(exception.getMessage(), containsString("API_KEY"));
    }

    @Test
    void notImplementedTypesFailInsteadOfFallingBack() {
        for (OciAuthType type : new OciAuthType[]{OciAuthType.INSTANCE_PRINCIPAL, OciAuthType.RESOURCE_PRINCIPAL}) {
            TestTask task = validTask()
                .authType(Property.ofValue(type))
                .build();

            UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, () -> OciAuth.provider(runContextFactory.of(), task));

            assertThat(exception.getMessage(), containsString(type.name()));
        }
    }

    @Test
    void configFile(@TempDir Path dir) throws Exception {
        Path config = writeConfig(dir);
        TestTask task = configFileTask(config).build();
        RunContext runContext = runContextFactory.of();

        BasicAuthenticationDetailsProvider provider = OciAuth.provider(runContext, task);

        assertThat(provider instanceof ConfigFileAuthenticationDetailsProvider, is(true));
        ConfigFileAuthenticationDetailsProvider fromFile = (ConfigFileAuthenticationDetailsProvider) provider;
        assertThat(fromFile.getTenantId(), is(TENANCY));
        assertThat(fromFile.getUserId(), is(USER));
        assertThat(fromFile.getFingerprint(), is(FINGERPRINT));
        assertThat(fromFile.getPrivateKey() != null, is(true));
        assertThat(fromFile.getRegion().getRegionId(), is("eu-frankfurt-1"));
    }

    @Test
    void configFileProfile(@TempDir Path dir) throws Exception {
        TestTask task = configFileTask(writeConfig(dir))
            .profile(Property.ofValue("OTHER"))
            .build();

        ConfigFileAuthenticationDetailsProvider provider = (ConfigFileAuthenticationDetailsProvider) OciAuth.provider(runContextFactory.of(), task);

        assertThat(provider.getUserId(), is("ocid1.user.oc1..other"));
        assertThat(provider.getRegion().getRegionId(), is("us-ashburn-1"));
    }

    @Test
    void configFileMissingFileIsNamed(@TempDir Path dir) {
        Path missing = dir.resolve("nope");
        TestTask task = configFileTask(missing).build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> OciAuth.provider(runContextFactory.of(), task));

        assertThat(exception.getMessage(), containsString(missing.toString()));
    }

    @Test
    void configFileMissingProfileFails(@TempDir Path dir) throws Exception {
        TestTask task = configFileTask(writeConfig(dir))
            .profile(Property.ofValue("NOPE"))
            .build();

        Exception exception = assertThrows(Exception.class, () -> OciAuth.provider(runContextFactory.of(), task));

        assertThat(exception.getMessage(), containsString("NOPE"));
    }

    @Test
    void secretsAreNotPrintable() {
        TestTask task = validTask()
            .passphrase(Property.ofValue("s3cret"))
            .build();

        assertThat(task.toString(), not(containsString("BEGIN PRIVATE KEY")));
        assertThat(task.toString(), not(containsString("s3cret")));
    }

    private static TestTask.TestTaskBuilder<?, ?> validTask() {
        return TestTask.builder()
            .id("test")
            .type(TestTask.class.getName())
            .region(Property.ofValue("eu-frankfurt-1"))
            .tenancyOcid(Property.ofValue(TENANCY))
            .userId(Property.ofValue(USER))
            .fingerprint(Property.ofValue(FINGERPRINT))
            .privateKey(Property.ofValue(privateKey));
    }

    private static TestTask.TestTaskBuilder<?, ?> configFileTask(Path config) {
        return TestTask.builder()
            .id("test")
            .type(TestTask.class.getName())
            .authType(Property.ofValue(OciAuthType.CONFIG_FILE))
            .configFile(Property.ofValue(config.toString()));
    }

    /**
     * Writes a key file and an OCI config file with a DEFAULT and an OTHER profile into the directory, and returns the config path.
     */
    private static Path writeConfig(Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("key.pem"), privateKey);

        return Files.writeString(dir.resolve("config"), """
            [DEFAULT]
            user=%s
            fingerprint=%s
            tenancy=%s
            region=eu-frankfurt-1
            key_file=%s

            [OTHER]
            user=ocid1.user.oc1..other
            fingerprint=%s
            tenancy=%s
            region=us-ashburn-1
            key_file=%s
            """.formatted(USER, FINGERPRINT, TENANCY, key, FINGERPRINT, TENANCY, key));
    }

    // public with a no-arg constructor: Kestra's annotation processor registers every Task subclass as a plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class TestTask extends AbstractOciTask {
    }
}

package io.kestra.plugin.oci;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.auth.StringPrivateKeySupplier;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

import java.io.IOException;
import java.util.Optional;

/**
 * Builds the OCI SDK authentication provider that signs every request, from the connection properties of a task or trigger.
 */
public final class OciAuth {
    private static final String DEFAULT_PROFILE = "DEFAULT";

    private OciAuth() {
    }

    public static BasicAuthenticationDetailsProvider provider(RunContext runContext, OciConnectionInterface connection) throws IllegalVariableEvaluationException {
        OciAuthType authType = runContext.render(connection.getAuthType()).as(OciAuthType.class).orElse(OciAuthType.API_KEY);

        return switch (authType) {
            case API_KEY -> apiKey(runContext, connection);
            case CONFIG_FILE -> configFile(runContext, connection);
            case INSTANCE_PRINCIPAL, RESOURCE_PRINCIPAL ->
                throw new UnsupportedOperationException("The '" + authType + "' authentication type is not implemented yet, use 'API_KEY' or 'CONFIG_FILE'");
        };
    }

    private static SimpleAuthenticationDetailsProvider apiKey(RunContext runContext, OciConnectionInterface connection) throws IllegalVariableEvaluationException {
        String region = required(runContext, connection.getRegion(), "region");
        String tenancyOcid = required(runContext, connection.getTenancyOcid(), "tenancyOcid");
        String userId = required(runContext, connection.getUserId(), "userId");
        String fingerprint = required(runContext, connection.getFingerprint(), "fingerprint");
        String privateKey = required(runContext, connection.getPrivateKey(), "privateKey");
        Optional<String> passphrase = runContext.render(connection.getPassphrase()).as(String.class).filter(p -> !p.isBlank());

        SimpleAuthenticationDetailsProvider.SimpleAuthenticationDetailsProviderBuilder builder = SimpleAuthenticationDetailsProvider.builder()
            .tenantId(tenancyOcid)
            .userId(userId)
            .fingerprint(fingerprint)
            .privateKeySupplier(new StringPrivateKeySupplier(privateKey))
            .region(Region.fromRegionId(region));

        passphrase.ifPresent(p -> builder.passphraseCharacters(p.toCharArray()));

        return builder.build();
    }

    private static ConfigFileAuthenticationDetailsProvider configFile(RunContext runContext, OciConnectionInterface connection) throws IllegalVariableEvaluationException {
        Optional<String> path = runContext.render(connection.getConfigFile()).as(String.class).filter(value -> !value.isBlank());
        String profile = runContext.render(connection.getProfile()).as(String.class).filter(value -> !value.isBlank()).orElse(DEFAULT_PROFILE);

        try {
            // without a path, the SDK uses $OCI_CONFIG_FILE, then ~/.oci/config
            ConfigFileReader.ConfigFile config = path.isPresent()
                ? ConfigFileReader.parse(path.get(), profile)
                : ConfigFileReader.parseDefault(profile);

            return new ConfigFileAuthenticationDetailsProvider(config);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "Unable to read profile '" + profile + "' of the OCI config file" + path.map(p -> " '" + p + "'").orElse("") + ": " + e.getMessage(),
                e
            );
        }
    }

    private static String required(RunContext runContext, Property<String> property, String name) throws IllegalVariableEvaluationException {
        return runContext.render(property).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("'" + name + "' is required for the 'API_KEY' authentication type"));
    }
}

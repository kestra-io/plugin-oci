package io.kestra.plugin.oci;

import io.kestra.core.models.property.Property;

/**
 * Connection properties shared by every OCI task and trigger.
 * <p>
 * It is an interface, not a base class, so that triggers (which do not extend {@code Task}) can use it too.
 * See {@link OciAuth} to turn it into an SDK authentication provider.
 */
public interface OciConnectionInterface {
    Property<OciAuthType> getAuthType();

    Property<String> getRegion();

    Property<String> getTenancyOcid();

    Property<String> getUserId();

    Property<String> getFingerprint();

    Property<String> getPrivateKey();

    Property<String> getPassphrase();

    Property<String> getConfigFile();

    Property<String> getProfile();
}

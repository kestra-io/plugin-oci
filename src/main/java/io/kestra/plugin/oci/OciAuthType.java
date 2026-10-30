package io.kestra.plugin.oci;

/**
 * The way a task or trigger authenticates to Oracle Cloud Infrastructure.
 */
public enum OciAuthType {
    /**
     * An OCI API signing key passed directly as properties (tenancy, user, fingerprint, private key).
     */
    API_KEY,
    /**
     * Credentials read from an OCI config file (for example {@code ~/.oci/config}).
     */
    CONFIG_FILE,
    /**
     * Identity of the OCI compute instance Kestra runs on.
     */
    INSTANCE_PRINCIPAL,
    /**
     * Identity of the OCI resource (for example a function) Kestra runs in.
     */
    RESOURCE_PRINCIPAL
}

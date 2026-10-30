package io.kestra.plugin.oci;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractOciTask extends Task implements OciConnectionInterface {
    @Schema(
        title = "Authentication type",
        description = "How to authenticate to OCI. `API_KEY` signs requests with the OCI API signing key given in this task's properties."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<OciAuthType> authType = Property.ofValue(OciAuthType.API_KEY);

    @Schema(
        title = "OCI region identifier",
        description = "For example `eu-frankfurt-1` or `us-ashburn-1`."
    )
    @PluginProperty(group = "connection")
    protected Property<String> region;

    @Schema(title = "OCID of the tenancy", description = "Required for the `API_KEY` authentication type.")
    @PluginProperty(group = "connection")
    protected Property<String> tenancyOcid;

    @Schema(title = "OCID of the calling user", description = "Required for the `API_KEY` authentication type.")
    @PluginProperty(group = "connection")
    protected Property<String> userId;

    @Schema(title = "Fingerprint of the API signing key", description = "Required for the `API_KEY` authentication type.")
    @PluginProperty(group = "connection")
    protected Property<String> fingerprint;

    @Schema(title = "Private key in PEM format", description = "Required for the `API_KEY` authentication type.")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "connection", secret = true)
    protected Property<String> privateKey;

    @Schema(title = "Passphrase of the private key", description = "Only needed if the private key is encrypted.")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "advanced", secret = true)
    protected Property<String> passphrase;

    @Schema(title = "Path to the OCI config file", description = "For the `CONFIG_FILE` authentication type.")
    @PluginProperty(group = "advanced")
    protected Property<String> configFile;

    @Schema(title = "Profile name in the OCI config file", description = "For the `CONFIG_FILE` authentication type.")
    @PluginProperty(group = "advanced")
    protected Property<String> profile;
}

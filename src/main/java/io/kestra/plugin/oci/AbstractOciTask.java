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
        description = "How to authenticate to OCI. `API_KEY` signs requests with the OCI API signing key given in this task's properties. `CONFIG_FILE` reads an OCI config file. `INSTANCE_PRINCIPAL` uses the identity of the OCI compute instance Kestra runs on, and `RESOURCE_PRINCIPAL` that of the OCI resource (for example a function) it runs in; both need no other property, and the region is detected."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<OciAuthType> authType = Property.ofValue(OciAuthType.API_KEY);

    @Schema(
        title = "OCI region identifier",
        description = "For example `eu-frankfurt-1` or `us-ashburn-1`. Required for the `API_KEY` authentication type. With `CONFIG_FILE`, the region of the config file profile is used and this property is not."
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

    @Schema(title = "Passphrase of the private key", description = "Only needed if the private key is encrypted. Used by the `API_KEY` authentication type; with `CONFIG_FILE`, set `pass_phrase` in the config file instead.")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "advanced", secret = true)
    protected Property<String> passphrase;

    @Schema(
        title = "Path to the OCI config file",
        description = "For the `CONFIG_FILE` authentication type. Defaults to the file named by the `OCI_CONFIG_FILE` environment variable, then `~/.oci/config`. The file is read on the machine that runs the task."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> configFile;

    @Schema(title = "Profile name in the OCI config file", description = "For the `CONFIG_FILE` authentication type. Defaults to `DEFAULT`.")
    @PluginProperty(group = "advanced")
    protected Property<String> profile;
}

package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsInvoke;
import com.oracle.bmc.functions.FunctionsInvokeClient;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.FunctionsManagementClient;
import io.kestra.plugin.oci.AbstractOciTask;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFunctionsTask extends AbstractOciTask {
    // a seam for tests: they return a client that does not call OCI
    protected FunctionsManagement client(BasicAuthenticationDetailsProvider provider) {
        return FunctionsManagementClient.builder().build(provider);
    }

    // a function is invoked through its own endpoint, with a different client than the management one
    protected FunctionsInvoke invokeClient(BasicAuthenticationDetailsProvider provider, String invokeEndpoint) {
        return FunctionsInvokeClient.builder().endpoint(invokeEndpoint).build(provider);
    }
}

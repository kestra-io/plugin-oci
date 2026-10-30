package io.kestra.plugin.oci.functions;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.functions.FunctionsInvoke;
import com.oracle.bmc.functions.FunctionsInvokeClient;
import com.oracle.bmc.functions.FunctionsManagement;
import com.oracle.bmc.functions.FunctionsManagementClient;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.requests.GetFunctionRequest;
import com.oracle.bmc.functions.requests.InvokeFunctionRequest;
import com.oracle.bmc.functions.responses.InvokeFunctionResponse;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.BiFunction;

/**
 * Invokes a deployed function. Shared by the {@link InvokeFunction} task and the {@link FunctionInvocationTrigger} trigger, which
 * cannot share a base class because a trigger is not a task.
 */
final class FunctionInvoker {
    // above this size, the response is stored in the internal storage instead of being returned in the output
    static final long MAX_BODY_BYTES = 1024 * 1024;

    private FunctionInvoker() {
    }

    record Result(int statusCode, String body, URI uri, long size, String opcRequestId) {
    }

    static FunctionsManagement managementClient(BasicAuthenticationDetailsProvider provider) {
        return FunctionsManagementClient.builder().build(provider);
    }

    // a function is invoked through its own endpoint, with a different client than the management one
    static FunctionsInvoke invokeClient(BasicAuthenticationDetailsProvider provider, String invokeEndpoint) {
        return FunctionsInvokeClient.builder().endpoint(invokeEndpoint).build(provider);
    }

    /**
     * @param body the already rendered body: a string, an object to send as JSON, or {@code null} for no input
     */
    static Result invoke(
        RunContext runContext,
        BasicAuthenticationDetailsProvider provider,
        String functionId,
        Object body,
        InvokeFunction.InvokeType invokeType,
        java.util.function.Function<BasicAuthenticationDetailsProvider, FunctionsManagement> managementClients,
        BiFunction<BasicAuthenticationDetailsProvider, String, FunctionsInvoke> invokeClients
    ) throws Exception {
        byte[] requestBody = requestBody(body);

        // the invoke endpoint is specific to each function, so it has to be looked up first
        String invokeEndpoint;
        try (FunctionsManagement management = managementClients.apply(provider)) {
            Function function = management.getFunction(GetFunctionRequest.builder().functionId(functionId).build()).getFunction();
            if (function.getInvokeEndpoint() == null || function.getInvokeEndpoint().isBlank()) {
                throw new IllegalStateException("Function '" + functionId + "' has no invoke endpoint, its state is " + function.getLifecycleState());
            }
            invokeEndpoint = function.getInvokeEndpoint();
        }

        InvokeFunctionRequest.Builder request = InvokeFunctionRequest.builder()
            .functionId(functionId)
            .fnInvokeType(invokeType == InvokeFunction.InvokeType.DETACHED ? InvokeFunctionRequest.FnInvokeType.Detached : InvokeFunctionRequest.FnInvokeType.Sync);
        if (requestBody != null) {
            request.invokeFunctionBody(new ByteArrayInputStream(requestBody));
        }

        try (FunctionsInvoke invoke = invokeClients.apply(provider, invokeEndpoint)) {
            InvokeFunctionResponse response = invoke.invokeFunction(request.build());

            Result result = read(runContext, response);
            runContext.logger().info("Function '{}' invoked, status {}", functionId, result.statusCode());
            return result;
        }
    }

    private static byte[] requestBody(Object body) throws Exception {
        if (body == null) {
            return null;
        }

        String text = body instanceof String string ? string : JacksonMapper.ofJson().writeValueAsString(body);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Result read(RunContext runContext, InvokeFunctionResponse response) throws Exception {
        // the response is streamed to a file first, so a big one never has to fit in memory
        File file = runContext.workingDir().createTempFile(".out").toFile();
        try (InputStream stream = response.getInputStream()) {
            if (stream != null) {
                Files.copy(stream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        long size = file.length();
        if (size > MAX_BODY_BYTES) {
            return new Result(response.get__httpStatusCode__(), null, runContext.storage().putFile(file), size, response.getOpcRequestId());
        }

        return new Result(response.get__httpStatusCode__(), Files.readString(file.toPath(), StandardCharsets.UTF_8), null, size, response.getOpcRequestId());
    }
}

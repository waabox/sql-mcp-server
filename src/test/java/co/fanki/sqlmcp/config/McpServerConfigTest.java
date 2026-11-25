package co.fanki.sqlmcp.config;

import co.fanki.sqlmcp.auth.domain.AccessPolicy;
import co.fanki.sqlmcp.auth.domain.Caller;
import co.fanki.sqlmcp.auth.domain.ConnectionAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the caller check that {@link McpServerConfig} wraps around every tool.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class McpServerConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean handlerCalled = new AtomicBoolean(false);

    @SuppressWarnings("deprecation")
    private McpStatelessServerFeatures.SyncToolSpecification wrappedTool() {
        final ConnectionProfileRepository repository = new ConnectionProfileRepository();
        final ConnectionAccess access = new ConnectionAccess(
                repository, new AccessPolicy(repository), email -> java.util.Set.of());
        final McpServerFeatures.SyncToolSpecification spec = new McpServerFeatures.SyncToolSpecification(
                new Tool("probe", "probe", "{\"type\":\"object\",\"properties\":{},\"required\":[]}"),
                (exchange, request) -> {
                    handlerCalled.set(true);
                    return new CallToolResult(List.of(new TextContent("ran")), false);
                });
        return McpServerConfig.toStateless(spec, access, MAPPER);
    }

    @SuppressWarnings("deprecation")
    private CallToolResult call(final McpTransportContext context) {
        return wrappedTool().callHandler().apply(context, new CallToolRequest("probe", Map.of()));
    }

    @Test
    void whenCallingTool_givenNoCallerInContext_shouldAnswerUnauthorizedWithoutRunningTheTool() {
        final CallToolResult result = call(McpTransportContext.create(Map.of()));

        assertTrue(result.isError());
        assertTrue(((TextContent) result.content().get(0)).text().contains("Unauthorized"));
        assertFalse(handlerCalled.get());
    }

    @Test
    void whenCallingTool_givenCallerInContext_shouldRunTheTool() {
        final CallToolResult result = call(McpTransportContext.create(
                Map.of(McpServerConfig.CALLER_CONTEXT_KEY, Caller.sharedToken())));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals("ran", ((TextContent) result.content().get(0)).text());
        assertTrue(handlerCalled.get());
    }

}

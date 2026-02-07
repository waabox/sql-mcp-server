package co.fanki.sqlmcp.connection.application;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MCP tool for listing all configured database connections.
 *
 * <p>Returns a list of connection profiles with their configuration details
 * (excluding sensitive information like passwords).
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class ListConnectionsTool {

    private static final String TOOL_NAME = "list_connections";
    private static final String TOOL_DESCRIPTION =
            "Lists all configured database connection profiles. " +
            "Returns name, type, host, port, database, and read-only status for each connection.";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private final ConnectionProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new ListConnectionsTool.
     *
     * @param profileRepository the repository for connection profiles
     * @param objectMapper the JSON object mapper
     */
    public ListConnectionsTool(
            final ConnectionProfileRepository profileRepository,
            final ObjectMapper objectMapper) {
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Creates the tool specification for listing connections.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification listConnectionsToolSpecification() {
        Tool tool = new Tool(TOOL_NAME, TOOL_DESCRIPTION, SCHEMA);

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    List<ConnectionProfile> profiles = profileRepository.findAll();

                    List<Map<String, Object>> connectionList = profiles.stream()
                            .map(this::toSafeMap)
                            .collect(Collectors.toList());

                    Map<String, Object> result = Map.of(
                            "connections", connectionList,
                            "count", connectionList.size()
                    );

                    try {
                        String json = objectMapper.writeValueAsString(result);
                        return new CallToolResult(List.of(new TextContent(json)), false);
                    } catch (JsonProcessingException e) {
                        return new CallToolResult(
                                List.of(new TextContent("Error serializing connections: " + e.getMessage())),
                                true
                        );
                    }
                }
        );
    }

    private Map<String, Object> toSafeMap(final ConnectionProfile profile) {
        return Map.of(
                "name", profile.name(),
                "type", profile.type().name(),
                "host", profile.host() != null ? profile.host() : "",
                "port", profile.port(),
                "database", profile.database(),
                "schema", profile.schema() != null ? profile.schema() : "",
                "readOnly", profile.readOnly()
        );
    }

}

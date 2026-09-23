package co.fanki.sqlmcp.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Applies transport-dependent defaults before the application context starts.
 *
 * <p>Business rule: in STDIO mode the embedded HTTP server only serves
 * {@code /health}, and every Claude session starts its own process. The HTTP
 * port therefore defaults to {@code 0} (a random free port), so several
 * sessions, or another service already using 8080, never collide. In HTTP mode
 * the Spring Boot default (8080) applies. An explicit {@code server.port} always
 * wins because these defaults have the lowest precedence.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class TransportDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "sqlMcpTransportDefaults";

    /**
     * Adds the STDIO defaults when the transport is STDIO.
     *
     * @param environment the environment being prepared
     * @param application the application being started
     */
    @Override
    public void postProcessEnvironment(
            final ConfigurableEnvironment environment,
            final SpringApplication application) {
        String transport = environment.getProperty("sql-mcp.transport", "http");
        if ("stdio".equalsIgnoreCase(transport.strip())) {
            environment.getPropertySources().addLast(
                    new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of("server.port", "0")));
        }
    }

    /**
     * Runs after the config files have been loaded, so the transport set in
     * {@code application.yml} is visible.
     *
     * @return the lowest precedence
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

}

package co.fanki.sqlmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Main entry point for the SQL MCP Server.
 *
 * <p>This application exposes SQL databases to LLMs through the Model Context Protocol (MCP).
 * It provides tools for schema introspection, safe query execution, and query explanation.
 *
 * <p>DataSourceAutoConfiguration is excluded because we manage our own DataSources
 * via {@link co.fanki.sqlmcp.connection.domain.DataSourceFactory} using connection profiles.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class SqlMcpServerApplication {

    /**
     * Starts the SQL MCP Server application.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(SqlMcpServerApplication.class, args);
    }

}

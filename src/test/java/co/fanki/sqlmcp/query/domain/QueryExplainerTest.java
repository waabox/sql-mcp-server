package co.fanki.sqlmcp.query.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import co.fanki.sqlmcp.connection.domain.DataSourceFactory;
import co.fanki.sqlmcp.connection.domain.DatabaseType;
import co.fanki.sqlmcp.observability.domain.QueryLogger;
import co.fanki.sqlmcp.query.domain.QueryExplainer.ExplainException;
import co.fanki.sqlmcp.query.domain.QueryExplainer.ExplainFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryExplainer}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class QueryExplainerTest {

    private static final String CONNECTION = "redshift";
    private static final String QUERY = "SELECT id FROM users";

    private DataSourceFactory dataSourceFactory;
    private QueryExecutor queryExecutor;
    private QueryExplainer explainer;

    @BeforeEach
    void setUp() {
        ConnectionProfileRepository repository = new ConnectionProfileRepository();
        repository.register(ConnectionProfile.create(
                CONNECTION, DatabaseType.REDSHIFT, "cluster.example.com", 5439, "dw",
                "user", "pass", null, true
        ));
        dataSourceFactory = createMock(DataSourceFactory.class);
        queryExecutor = createMock(QueryExecutor.class);
        QueryLogger queryLogger = createMock(QueryLogger.class);
        replay(queryLogger);
        explainer = new QueryExplainer(dataSourceFactory, repository, new QueryGuard(), queryExecutor, queryLogger);
    }

    @Test
    void whenBuildingExplainQuery_givenRedshift_shouldUsePlainExplain() {
        String explain = explainer.buildExplainQuery(DatabaseType.REDSHIFT, QUERY, false, ExplainFormat.TEXT);

        assertEquals("EXPLAIN " + QUERY, explain);
    }

    @Test
    void whenExplainingWithAnalyze_givenRedshiftConnection_shouldFailWithClearMessage() {
        replay(dataSourceFactory, queryExecutor);

        ExplainException error = assertThrows(ExplainException.class,
                () -> explainer.explain(CONNECTION, QUERY, true, ExplainFormat.TEXT));

        assertTrue(error.getMessage().contains("Redshift"));
        verify(dataSourceFactory, queryExecutor);
    }

    @Test
    void whenExplainingWithJsonFormat_givenRedshiftConnection_shouldFailWithClearMessage() {
        replay(dataSourceFactory, queryExecutor);

        ExplainException error = assertThrows(ExplainException.class,
                () -> explainer.explain(CONNECTION, QUERY, false, ExplainFormat.JSON));

        assertTrue(error.getMessage().contains("Redshift"));
        verify(dataSourceFactory, queryExecutor);
    }

}

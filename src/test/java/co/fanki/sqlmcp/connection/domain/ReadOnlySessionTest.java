package co.fanki.sqlmcp.connection.domain;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

/**
 * Unit tests for {@link ReadOnlySession}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class ReadOnlySessionTest {

    @Test
    void whenOpening_givenRedshift_shouldSetOnlyTheTransactionStatementTimeout() throws Exception {
        Connection connection = createMock(Connection.class);
        Statement statement = createMock(Statement.class);

        expect(connection.getAutoCommit()).andReturn(false);
        expect(connection.isReadOnly()).andReturn(true);
        expect(connection.createStatement()).andReturn(statement);
        expect(statement.execute("SET LOCAL statement_timeout = 30000")).andReturn(false);
        statement.close();
        expectLastCall();
        replay(connection, statement);

        ReadOnlySession.open(connection, DatabaseType.REDSHIFT, 30_000, 5_000);

        verify(connection, statement);
    }

}

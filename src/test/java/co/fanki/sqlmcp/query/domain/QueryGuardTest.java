package co.fanki.sqlmcp.query.domain;

import co.fanki.sqlmcp.connection.domain.DatabaseType;
import co.fanki.sqlmcp.query.domain.QueryGuard.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryGuard}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class QueryGuardTest {

    private QueryGuard guard;

    @BeforeEach
    void setUp() {
        guard = new QueryGuard();
    }

    private ValidationResult pg(final String query) {
        return guard.validate(query, DatabaseType.POSTGRESQL);
    }

    private ValidationResult mysql(final String query) {
        return guard.validate(query, DatabaseType.MYSQL);
    }

    // -------------------------------------------------------------------------
    // Legitimate debugging queries must pass
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM orders WHERE status = 'A' OR type = 'B'",
            "SELECT id FROM a UNION ALL SELECT id FROM b",
            "SELECT * FROM audit_events WHERE event_type = 'UPDATE'",
            "SELECT * FROM audit_events WHERE action = 'DELETE' OR action = 'INSERT'",
            "SELECT * FROM logs WHERE message LIKE '%--%'",
            "SELECT * FROM logs WHERE message = 'drop table; create'",
            "SELECT extract(year FROM created_at), count(*) FROM orders GROUP BY 1",
            "SELECT substring(name FROM 1 FOR 3) FROM customers",
            "SELECT * FROM pg_stat_activity WHERE state <> 'idle'",
            "SELECT * FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid",
            "SELECT * FROM information_schema.columns WHERE table_name = 'orders'",
            "WITH recent AS (SELECT * FROM orders) SELECT * FROM recent",
            "(SELECT 1)",
            "SELECT 1;",
            "SELECT 1; -- trailing comment",
            "SELECT updated_at, deleted_at FROM orders",
            "SELECT \"update\" FROM weird_table",
            "SELECT $$ DELETE FROM x; $$ AS text",
            "SELECT a IS DISTINCT FROM b FROM t",
            "SELECT replace(name, 'a', 'b') FROM customers",
            "SELECT * FROM orders -- DELETE FROM orders\n WHERE id = 1"
    })
    void whenValidating_givenLegitimateReadQuery_shouldAccept(final String query) {
        ValidationResult result = pg(query);
        assertTrue(result.isValid(), () -> query + " -> " + result.error());
    }

    // -------------------------------------------------------------------------
    // Harmful queries must be rejected
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM orders",
            "UPDATE orders SET status = 'x'",
            "INSERT INTO orders VALUES (1)",
            "DROP TABLE orders",
            "SELECT 1; DROP TABLE orders",
            "SELECT 1; SELECT 2",
            "WITH d AS (DELETE FROM orders RETURNING *) SELECT * FROM d",
            "SELECT * INTO backup FROM orders",
            "SELECT * FROM orders FOR UPDATE",
            "SELECT * FROM orders FOR NO KEY UPDATE",
            "SELECT * FROM orders FOR SHARE",
            "SELECT * FROM orders FOR KEY SHARE",
            "SELECT pg_terminate_backend(123)",
            "SELECT pg_cancel_backend(123)",
            "SELECT pg_catalog.pg_terminate_backend(123)",
            "SELECT \"pg_terminate_backend\"(123)",
            "SELECT pg_sleep(600)",
            "SELECT set_config('statement_timeout', '0', false)",
            "SELECT pg_advisory_lock(1)",
            "SELECT pg_try_advisory_xact_lock(1)",
            "SELECT nextval('orders_id_seq')",
            "SELECT pg_stat_statements_reset()",
            "SELECT pg_stat_reset()",
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT lo_export(1, '/tmp/x')",
            "SELECT dblink_exec('dbname=x', 'DELETE FROM t')",
            "SELECT query_to_xml('DELETE FROM t', true, true, '')",
            "SELECT * FROM pg_terminate_backend(1)",
            "SELECT 'unterminated",
            "SELECT /* unterminated"
    })
    void whenValidating_givenHarmfulQuery_shouldReject(final String query) {
        ValidationResult result = pg(query);
        assertFalse(result.isValid(), query);
    }

    @Test
    void whenValidating_givenPostgresNestedComment_shouldTreatInnerTextAsComment() {
        assertTrue(pg("SELECT 1 /* outer /* inner */ DELETE */").isValid());
    }

    @Test
    void whenValidating_givenPostgresBackslashInStandardString_shouldNotTreatItAsEscape() {
        // In PostgreSQL 'a\' is a complete string, so the DELETE is real SQL.
        assertFalse(pg("SELECT 'a\\' ; DELETE FROM orders; SELECT '").isValid());
    }

    @Test
    void whenValidating_givenPostgresEscapeString_shouldHonorBackslashEscape() {
        assertTrue(pg("SELECT E'it\\'s ; DELETE' AS x").isValid());
    }

    @Test
    void whenValidating_givenMysqlBackslashEscape_shouldKeepStringOpen() {
        assertTrue(mysql("SELECT 'it\\'s ; DELETE' AS x").isValid());
    }

    @Test
    void whenValidating_givenMysqlExecutableComment_shouldReject() {
        assertFalse(mysql("SELECT 1 /*! , (SELECT SLEEP(10)) */").isValid());
    }

    @Test
    void whenValidating_givenMysqlLockingClauses_shouldReject() {
        assertFalse(mysql("SELECT * FROM orders LOCK IN SHARE MODE").isValid());
        assertFalse(mysql("SELECT SLEEP(10)").isValid());
        assertFalse(mysql("SELECT GET_LOCK('x', 10)").isValid());
    }

    @Test
    void whenValidating_givenMysqlDoubleDashWithoutSpace_shouldNotTreatItAsComment() {
        // In MySQL "--x" is not a comment, so the statement separator is real.
        assertFalse(mysql("SELECT 1 --x; DELETE FROM orders").isValid());
    }

    @Test
    void whenValidating_givenMysqlHashComment_shouldIgnoreIt() {
        assertTrue(mysql("SELECT 1 # DELETE FROM orders").isValid());
    }

    // -------------------------------------------------------------------------
    // Table access control
    // -------------------------------------------------------------------------

    @Test
    void whenValidating_givenDeniedTableInSubquery_shouldReject() {
        guard.setDenyList(List.of("credentials"));
        assertFalse(pg("SELECT * FROM (SELECT * FROM credentials) c").isValid());
    }

    @Test
    void whenValidating_givenDeniedTableInCte_shouldReject() {
        guard.setDenyList(List.of("credentials"));
        assertFalse(pg("WITH x AS (SELECT * FROM credentials) SELECT * FROM x").isValid());
    }

    @Test
    void whenValidating_givenDeniedTableSchemaQualified_shouldReject() {
        guard.setDenyList(List.of("credentials"));
        assertFalse(pg("SELECT * FROM public.credentials").isValid());
        assertFalse(pg("SELECT * FROM \"public\".\"credentials\"").isValid());
    }

    @Test
    void whenValidating_givenDeniedTableInCommaJoin_shouldReject() {
        guard.setDenyList(List.of("credentials"));
        assertFalse(pg("SELECT * FROM orders o, credentials c WHERE o.id = c.id").isValid());
    }

    @Test
    void whenValidating_givenDeniedTableAfterJoinCondition_shouldReject() {
        guard.setDenyList(List.of("credentials"));
        assertFalse(pg("SELECT * FROM a JOIN b ON a.id = b.id, credentials c").isValid());
    }

    @Test
    void whenValidating_givenDeniedTableInWhereSubquery_shouldReject() {
        guard.setDenyList(List.of("credentials"));
        assertFalse(pg("SELECT * FROM orders WHERE id IN (SELECT id FROM credentials)").isValid());
    }

    @Test
    void whenValidating_givenDeniedSchemaWildcard_shouldReject() {
        guard.setDenyList(List.of("secret.*"));
        assertFalse(pg("SELECT * FROM secret.keys").isValid());
        assertTrue(pg("SELECT * FROM public.keys").isValid());
    }

    @Test
    void whenValidating_givenCteShadowingDeniedName_shouldAccept() {
        guard.setDenyList(List.of("credentials"));
        assertTrue(pg("WITH credentials AS (SELECT 1 AS id) SELECT * FROM credentials").isValid());
    }

    @Test
    void whenValidating_givenAllowListAndFunctionFrom_shouldNotTreatColumnsAsTables() {
        guard.setAllowList(List.of("orders"));
        assertTrue(pg("SELECT extract(year FROM created_at) FROM orders").isValid());
        assertFalse(pg("SELECT * FROM customers").isValid());
    }

    @Test
    void whenCheckingTableAccess_givenDeniedTable_shouldReturnFalse() {
        guard.setDenyList(List.of("credentials"));
        assertFalse(guard.isTableAllowed("public", "credentials"));
        assertFalse(guard.isTableAllowed(null, "credentials"));
        assertTrue(guard.isTableAllowed("public", "orders"));
    }

    @Test
    void whenValidating_givenRejectedQuery_shouldExplainReason() {
        assertEquals("Multiple statements are not allowed", pg("SELECT 1; SELECT 2").error());
    }

}

package co.fanki.sqlmcp.connection.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DatabaseType}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class DatabaseTypeTest {

    @Test
    void whenGettingDriverClassName_givenRedshift_shouldReturnAmazonRedshiftDriver() {
        assertEquals("com.amazon.redshift.jdbc42.Driver", DatabaseType.REDSHIFT.driverClassName());
    }

    @Test
    void whenBuildingUrl_givenRedshift_shouldUseRedshiftJdbcScheme() {
        String url = DatabaseType.REDSHIFT.buildUrl("cluster.example.com", 5439, "dw");

        assertEquals("jdbc:redshift://cluster.example.com:5439/dw", url);
    }

    @Test
    void whenGettingDefaultPort_givenRedshift_shouldReturn5439() {
        assertEquals(5439, DatabaseType.REDSHIFT.defaultPort());
    }

    @Test
    void whenCreatingProfile_givenRedshiftWithoutPort_shouldUseRedshiftDefaultPort() {
        ConnectionProfile profile = ConnectionProfile.create(
                "redshift", DatabaseType.REDSHIFT, "cluster.example.com", 0, "dw",
                "user", "pass", null, true
        );

        assertEquals("jdbc:redshift://cluster.example.com:5439/dw", profile.jdbcUrl());
    }

    @Test
    void whenCreatingProfile_givenRedshiftWithExplicitPort_shouldKeepConfiguredPort() {
        ConnectionProfile profile = ConnectionProfile.create(
                "redshift", DatabaseType.REDSHIFT, "cluster.example.com", 5432, "dw",
                "user", "pass", null, true
        );

        assertEquals("jdbc:redshift://cluster.example.com:5432/dw", profile.jdbcUrl());
    }

}

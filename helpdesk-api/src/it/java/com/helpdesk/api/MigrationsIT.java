package com.helpdesk.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que las migraciones Flyway se aplican sobre PostgreSQL 17 real.
 * Ejecutar con: mvn verify -Pintegration  (requiere Docker).
 */
@Tag("integration")
@Testcontainers
class MigrationsIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Test
    void aplicaMigracionesYDatosDeReferencia() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT (SELECT count(*) FROM tipos_falla), (SELECT count(*) FROM especialidades), (SELECT count(*) FROM prioridades)")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(18);
            assertThat(rs.getInt(2)).isEqualTo(12);
            assertThat(rs.getInt(3)).isEqualTo(4);
        }
    }
}

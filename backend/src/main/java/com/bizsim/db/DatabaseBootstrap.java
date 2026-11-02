package com.bizsim.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates the empty database on first start if it does not exist.
 *
 * <p>Flyway creates every table, index and constraint on every boot — that part is already automatic.
 * What Flyway cannot do is create the database it connects to, so a fresh PostgreSQL otherwise needs
 * one manual command before the app can start. This initializer runs before the context (and so
 * before the DataSource and Flyway), connects to the maintenance database {@code postgres}, and
 * issues {@code CREATE DATABASE} only when it is missing.
 *
 * <p>Deliberately fail-soft: in production the application role often has no CREATEDB right, and a
 * hard failure here would be a confusing startup error. It logs and lets Flyway produce the real
 * message. Disable with {@code bizsim.db.create-if-missing=false}.
 *
 * <p>This is not a substitute for migrations. {@code ddl-auto} is never used: a schema that changes
 * itself is unreviewable, and a product with tenants and audit tables needs ordered, versioned
 * migrations that can be replayed on a copy of production.
 */
public class DatabaseBootstrap implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

    private static final Pattern JDBC = Pattern.compile("^jdbc:postgresql://([^/]+)/([^?]+)(\\?.*)?$");
    static final String MAINTENANCE_DATABASE = "postgres";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();
        if (!env.getProperty("bizsim.db.create-if-missing", Boolean.class, Boolean.TRUE)) {
            return;
        }

        String url = env.getProperty("spring.datasource.url");
        Optional<String> maintenance = maintenanceUrl(url);
        if (maintenance.isEmpty()) {
            return;
        }

        String user = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String database = databaseName(url).orElseThrow();

        try (Connection connection = DriverManager.getConnection(maintenance.get(), user, password)) {
            if (exists(connection, database)) {
                log.info("db: database '{}' exists; Flyway will apply any pending migrations", database);
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("create database \"" + database.replace("\"", "\"\"") + "\"");
            }
            log.info("db: created empty database '{}'; Flyway creates the schema next", database);
        } catch (SQLException e) {
            log.warn("db: could not prepare database '{}': {}. The application will start only if the "
                    + "database already exists; otherwise create it with "
                    + "\"createdb -h <host> -U <user> {}\"", database, e.getMessage(), database);
        }
    }

    /** Same server, same options, but connected to the maintenance database. */
    static Optional<String> maintenanceUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Optional.empty();
        }
        Matcher matcher = JDBC.matcher(jdbcUrl.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String database = matcher.group(2);
        if (MAINTENANCE_DATABASE.equals(database)) {
            return Optional.empty();
        }
        String params = matcher.group(3) == null ? "" : matcher.group(3);
        return Optional.of("jdbc:postgresql://" + matcher.group(1) + "/" + MAINTENANCE_DATABASE + params);
    }

    static Optional<String> databaseName(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Optional.empty();
        }
        Matcher matcher = JDBC.matcher(jdbcUrl.trim());
        return matcher.matches() ? Optional.of(matcher.group(2)) : Optional.empty();
    }

    private static boolean exists(Connection connection, String database) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select 1 from pg_database where datname = ?")) {
            query.setString(1, database);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }
}

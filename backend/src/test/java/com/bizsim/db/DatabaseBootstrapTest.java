package com.bizsim.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL handling only: exercising the CREATE DATABASE path needs a server that lacks the target
 * database, which is not portable across environments.
 */
class DatabaseBootstrapTest {

    private static final String SERVER = "jdbc:postgresql://localhost:5433";

    @Test
    void connects_to_the_maintenance_database_on_the_same_server() {
        assertThat(DatabaseBootstrap.maintenanceUrl(SERVER + "/bizsim"))
                .contains(SERVER + "/postgres");
    }

    @Test
    void keeps_query_options_when_switching_database() {
        assertThat(DatabaseBootstrap.maintenanceUrl(SERVER + "/bizsim?sslmode=require"))
                .contains(SERVER + "/postgres?sslmode=require");
    }

    @Test
    void does_nothing_when_already_connected_to_the_maintenance_database() {
        assertThat(DatabaseBootstrap.maintenanceUrl(SERVER + "/postgres")).isEmpty();
    }

    @Test
    void ignores_a_url_it_does_not_understand() {
        assertThat(DatabaseBootstrap.maintenanceUrl(null)).isEmpty();
        assertThat(DatabaseBootstrap.maintenanceUrl("jdbc:mysql://localhost/bizsim")).isEmpty();
        assertThat(DatabaseBootstrap.maintenanceUrl("not a url")).isEmpty();
    }

    @Test
    void extracts_the_database_name() {
        assertThat(DatabaseBootstrap.databaseName(SERVER + "/bizsim")).contains("bizsim");
        assertThat(DatabaseBootstrap.databaseName(SERVER + "/bizsim?x=1")).contains("bizsim");
        assertThat(DatabaseBootstrap.databaseName("nonsense")).isEmpty();
    }
}

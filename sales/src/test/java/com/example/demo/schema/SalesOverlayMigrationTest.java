package com.example.demo.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(
    named = "sales.postgres.integration", matches = "true")
class SalesOverlayMigrationTest {

    private static final String ADMIN_URL = System.getProperty(
        "sales.postgres.admin-url",
        "jdbc:postgresql://127.0.0.1:55432/postgres");
    private static final String USER = System.getProperty(
        "sales.postgres.user", "postgres");
    private static final String PASSWORD = setting(
        "sales.postgres.password",
        "SALES_POSTGRES_PASSWORD",
        "");
    private static final String TARGET_URL = System.getProperty(
        "sales.postgres.target-url", "");

    private static String setting(
        String propertyName,
        String environmentName,
        String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null
            ? defaultValue
            : environmentValue;
    }

    @Test
    void migrationAppliesTwiceToFreshUpstreamSchema() throws Exception {
        if (!TARGET_URL.isBlank()) {
            try (Connection connection =
                     DriverManager.getConnection(
                         TARGET_URL, USER, PASSWORD)) {
                verifyMigration(connection);
            }
            return;
        }

        String database = "sales_overlay_"
            + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin =
                 DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }

        try {
            String databaseUrl = ADMIN_URL.substring(
                0, ADMIN_URL.lastIndexOf('/') + 1) + database;
            try (Connection connection =
                     DriverManager.getConnection(
                         databaseUrl, USER, PASSWORD)) {
                verifyMigration(connection);
            }
        } finally {
            dropDatabase(database);
        }
    }

    private void verifyMigration(Connection connection) throws Exception {
        executeSql(connection, """
            DROP TABLE IF EXISTS sales_idempotency_record CASCADE;
            DROP TABLE IF EXISTS sales_idempotency_subject CASCADE;
            """);
        execute(
            connection,
            Path.of("src/main/resources/schema.sql"));
        execute(
            connection,
            Path.of("src/main/resources/data.sql"));
        Path migration = Path.of(
            "../sales/sql/V001__customer_address_refund.sql");
        execute(connection, migration);
        execute(connection, migration);

        assertColumn(connection, "ord_aftersale",
            "apply_refund_amount", 19, 2, false);
        assertColumn(connection, "ref_refund",
            "actual_refund_amount", 19, 2, true);
        assertColumn(connection, "ord_order",
            "customer_id", 64, 0, false);
        assertConstraint(connection,
            "ord_aftersale", "fk_aftersale_customer_user");
        assertConstraint(connection,
            "ord_aftersale", "fk_aftersale_order_customer");
        assertConstraint(connection,
            "ord_aftersale", "fk_aftersale_item_order");
        assertConstraint(connection,
            "ord_aftersale", "fk_aftersale_refund");
        assertConstraint(connection,
            "ref_refund", "fk_refund_order_customer");
        assertConstraint(connection,
            "ref_refund", "fk_refund_item_order");
        assertConstraint(connection,
            "ref_refund", "ck_refund_restock_type");
        assertTable(connection, "sales_idempotency_subject");
        assertTable(connection, "sales_idempotency_record");
    }

    private void execute(Connection connection, Path path) throws Exception {
        executeSql(
            connection,
            Files.readString(path, StandardCharsets.UTF_8));
    }

    private void executeSql(Connection connection, String sql)
        throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertColumn(
        Connection connection,
        String table,
        String column,
        int precision,
        int scale,
        boolean nullable) throws Exception {
        try (var statement = connection.prepareStatement("""
            SELECT numeric_precision, numeric_scale, is_nullable
            FROM information_schema.columns
            WHERE table_schema='public'
              AND table_name=?
              AND column_name=?
            """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(precision, result.getInt("numeric_precision"));
                assertEquals(scale, result.getInt("numeric_scale"));
                assertEquals(
                    nullable ? "YES" : "NO",
                    result.getString("is_nullable"));
            }
        }
    }

    private void assertConstraint(
        Connection connection, String table, String constraint)
        throws Exception {
        try (var statement = connection.prepareStatement("""
            SELECT convalidated
            FROM pg_constraint
            WHERE conrelid=?::regclass AND conname=?
            """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), constraint);
                assertTrue(result.getBoolean("convalidated"), constraint);
            }
        }
    }

    private void assertTable(Connection connection, String table)
        throws Exception {
        try (var statement = connection.prepareStatement(
            "SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, "public." + table);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1), table);
            }
        }
    }

    private void dropDatabase(String database) throws Exception {
        try (Connection admin =
                 DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("""
                SELECT pg_terminate_backend(pid)
                FROM pg_stat_activity
                WHERE datname='%s' AND pid<>pg_backend_pid()
                """.formatted(database));
            statement.execute("DROP DATABASE IF EXISTS " + database);
        }
    }
}

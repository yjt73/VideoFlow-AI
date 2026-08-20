package com.example.server.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:flyway-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void createsCoreTablesAndIndexesOnAnEmptyDatabase() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(JDBC_URL, "sa", "")
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "")) {
            assertThat(tableExists(connection, "users")).isTrue();
            assertThat(tableExists(connection, "media_files")).isTrue();
            assertThat(constraintExists(connection, "users", "uk_users_username")).isTrue();
            assertThat(indexExists(connection, "media_files", "idx_media_user_upload_time")).isTrue();
            assertThat(columnExists(connection, "media_files", "content_hash")).isTrue();
            assertThat(indexExists(connection, "media_files", "idx_media_content_hash")).isTrue();
            assertThat(tableExists(connection, "agent_checkpoints")).isTrue();
            assertThat(columnExists(connection, "agent_checkpoints", "payload")).isTrue();
            assertThat(indexExists(connection, "agent_checkpoints", "idx_agent_checkpoint_updated")).isTrue();
            assertThat(tableExists(connection, "failed_analysis_tasks")).isTrue();
            assertThat(columnExists(connection, "failed_analysis_tasks", "mode")).isTrue();
            assertThat(indexExists(connection, "failed_analysis_tasks", "idx_failed_analysis_media")).isTrue();

            for (String legacyTable : List.of(
                    "analysis_tasks",
                    "outbox_events",
                    "analysis_task_failures",
                    "analysis_artifacts",
                    "storage_objects",
                    "multipart_upload_sessions",
                    "video_qa_conversations",
                    "video_qa_messages",
                    "rag_segments",
                    "embedding_cache",
                    "analysis_stage_checkpoints",
                    "model_raw_responses",
                    "analysis_quality_evaluations",
                    "storage_consistency_findings"
            )) {
                assertThat(tableExists(connection, legacyTable))
                        .as("legacy table %s should be removed", legacyTable)
                        .isFalse();
            }
            assertThat(columnExists(connection, "media_files", "object_id")).isFalse();
        }
    }

    @Test
    void contentHashCanBeReusedWithinAndAcrossUsers() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:flyway-content-hash;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, username, password, nickname)
                    VALUES (1, 'user-1', 'hash', '用户一'),
                           (2, 'user-2', 'hash', '用户二')
                    """);
            statement.executeUpdate(mediaInsertSql(1, "first.mp4"));
            statement.executeUpdate(mediaInsertSql(2, "same-content.mp4"));

            statement.executeUpdate(mediaInsertSql(1, "duplicate.mp4"));
        }
    }

    @Test
    void persistsCheckpointAndFailureInTheFourCoreTables() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:flyway-core-records;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, username, password, nickname)
                    VALUES (1, 'core-user', 'hash', '核心表测试用户')
                    """);
            statement.executeUpdate(mediaInsertSql(1, "core.mp4"));
            statement.executeUpdate("""
                    INSERT INTO agent_checkpoints (media_id, checkpoint_key, stage, payload)
                    VALUES (1, 'goal:demo:plan', 'PLAN_COMPLETED', '{"plan":[]}')
                    """);
            statement.executeUpdate("""
                    INSERT INTO failed_analysis_tasks (
                        media_id, action, mode, content_hash, user_goal, attempt_count,
                        error_type, error_message
                    ) VALUES (1, 'ANALYZE', 'GENERAL', 'hash', '测试失败任务', 1,
                              'TEST_FAILURE', '测试错误')
                    """);

            assertThat(rowCount(statement, "agent_checkpoints")).isEqualTo(1);
            assertThat(rowCount(statement, "failed_analysis_tasks")).isEqualTo(1);
        }
    }

    @Test
    void canRunMigrationMoreThanOnce() {
        Flyway flyway = Flyway.configure()
                .dataSource(JDBC_URL, "sa", "")
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.migrate().success).isTrue();
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws Exception {
        return indexNames(connection, tableName).stream()
                .anyMatch(indexName::equalsIgnoreCase);
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(null, null, tableName, columnName)) {
            return columns.next();
        }
    }

    private String mediaInsertSql(long userId, String filename) {
        return """
                INSERT INTO media_files (user_id, filename, status, file_path, content_hash)
                VALUES (%d, '%s', 'COMPLETED', 'http://minio/%s', '5d41402abc4b2a76b9719d911017c592')
                """.formatted(userId, filename, filename);
    }

    private void assertThatThrownBySql(SqlOperation operation) {
        org.assertj.core.api.Assertions.assertThatThrownBy(operation::run)
                .isInstanceOf(SQLException.class);
    }

    private int rowCount(Statement statement, String tableName) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            result.next();
            return result.getInt(1);
        }
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }

    private boolean constraintExists(Connection connection, String tableName, String constraintName) throws Exception {
        String sql = """
                SELECT 1
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE LOWER(TABLE_NAME) = LOWER(?)
                  AND LOWER(CONSTRAINT_NAME) = LOWER(?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName);
            try (ResultSet constraints = statement.executeQuery()) {
                return constraints.next();
            }
        }
    }

    private List<String> indexNames(Connection connection, String tableName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        List<String> indexNames = new ArrayList<>();
        try (ResultSet indexes = metaData.getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                String indexName = indexes.getString("INDEX_NAME");
                if (indexName != null) {
                    indexNames.add(indexName);
                }
            }
        }
        return indexNames;
    }
}

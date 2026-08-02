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
            assertThat(indexExists(connection, "media_files", "uk_media_user_content_hash")).isTrue();
            assertThat(tableExists(connection, "analysis_tasks")).isTrue();
            assertThat(tableExists(connection, "outbox_events")).isTrue();
            assertThat(tableExists(connection, "analysis_task_failures")).isTrue();
            assertThat(indexExists(
                    connection,
                    "analysis_tasks",
                    "uk_analysis_tasks_task_id"
            )).isTrue();
            assertThat(indexExists(
                    connection,
                    "analysis_tasks",
                    "idx_analysis_tasks_status_created"
            )).isTrue();
            assertThat(indexExists(
                    connection,
                    "analysis_tasks",
                    "idx_analysis_tasks_media_type"
            )).isTrue();
            assertThat(indexExists(
                    connection,
                    "outbox_events",
                    "uk_outbox_aggregate_event"
            )).isTrue();
            assertThat(tableExists(connection, "storage_objects")).isTrue();
            assertThat(columnExists(connection, "storage_objects", "content_hash")).isTrue();
            assertThat(columnExists(connection, "storage_objects", "reference_count")).isTrue();
            assertThat(columnExists(connection, "storage_objects", "status")).isTrue();
            assertThat(constraintExists(
                    connection,
                    "storage_objects",
                    "uk_storage_objects_hash"
            )).isTrue();
            assertThat(constraintExists(
                    connection,
                    "storage_objects",
                    "uk_storage_objects_location"
            )).isTrue();
            assertThat(tableExists(connection, "multipart_upload_sessions")).isTrue();
            assertThat(columnExists(
                    connection,
                    "multipart_upload_sessions",
                    "provider_upload_id"
            )).isTrue();
            assertThat(columnExists(
                    connection,
                    "multipart_upload_sessions",
                    "temporary_object_key"
            )).isTrue();
            assertThat(columnExists(connection, "multipart_upload_sessions", "expires_at")).isTrue();
            assertThat(tableExists(connection, "storage_consistency_findings")).isTrue();
            assertThat(columnExists(
                    connection,
                    "storage_consistency_findings",
                    "finding_type"
            )).isTrue();
            assertThat(columnExists(
                    connection,
                    "storage_consistency_findings",
                    "object_key"
            )).isTrue();
            assertThat(columnExists(
                    connection,
                    "storage_consistency_findings",
                    "status"
            )).isTrue();
            assertThat(columnExists(connection, "media_files", "object_id")).isTrue();
            assertThat(tableExists(connection, "video_qa_conversations")).isTrue();
            assertThat(tableExists(connection, "video_qa_messages")).isTrue();
            assertThat(constraintExists(
                    connection,
                    "video_qa_conversations",
                    "uk_video_qa_conversation_id"
            )).isTrue();
            assertThat(indexExists(
                    connection,
                    "video_qa_messages",
                    "idx_video_qa_messages_conversation_created"
            )).isTrue();
            assertThat(tableExists(connection, "rag_segments")).isTrue();
            assertThat(columnExists(connection, "rag_segments", "embedding_json")).isTrue();
            assertThat(constraintExists(
                    connection,
                    "rag_segments",
                    "uk_rag_segments_media_source_index"
            )).isTrue();
        }
    }

    @Test
    void contentHashIsUniqueWithinAUserButReusableByAnotherUser() throws Exception {
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

            assertThatThrownBySql(() -> statement.executeUpdate(mediaInsertSql(1, "duplicate.mp4")));
        }
    }

    @Test
    void deletingMediaKeepsCompletedUploadSessionAsDetachedAuditRecord() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:flyway-delete-media;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, username, password, nickname)
                    VALUES (1, 'delete-user', 'hash', '删除测试用户')
                    """);
            statement.executeUpdate(mediaInsertSql(1, "delete-me.mp4"));
            statement.executeUpdate("""
                    INSERT INTO multipart_upload_sessions (
                        session_id, provider_upload_id, user_id, filename,
                        content_hash, content_type, size_bytes, bucket_name,
                        temporary_object_key, total_parts, part_size, status,
                        media_id, expires_at
                    ) VALUES (
                        '00000000-0000-0000-0000-000000000001', 'provider-1', 1, 'delete-me.mp4',
                        '5d41402abc4b2a76b9719d911017c592', 'video/mp4', 1024, 'media',
                        'multipart/delete-me', 1, 1024, 'COMPLETED',
                        1, CURRENT_TIMESTAMP
                    )
                    """);

            statement.executeUpdate("DELETE FROM media_files WHERE id = 1");

            try (ResultSet result = statement.executeQuery("""
                    SELECT media_id
                    FROM multipart_upload_sessions
                    WHERE session_id = '00000000-0000-0000-0000-000000000001'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("media_id")).isNull();
            }
        }
    }

    @Test
    void deletingAnalyzedMediaCascadesOwnedDerivedDataAndDetachesRawAudit() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:flyway-delete-analyzed-media;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, username, password, nickname)
                    VALUES (1, 'analyzed-user', 'hash', '分析删除测试用户')
                    """);
            statement.executeUpdate(mediaInsertSql(1, "analyzed.mp4"));
            statement.executeUpdate("""
                    INSERT INTO analysis_tasks (task_id, media_id, user_id, task_type, status)
                    VALUES ('00000000-0000-0000-0000-000000000002', 1, 1, 'FULL_ANALYSIS', 'SUCCESS')
                    """);
            statement.executeUpdate("""
                    INSERT INTO analysis_stage_checkpoints (
                        task_id, media_id, task_type, stage, goal_hash,
                        input_hash, model_version, status
                    ) VALUES ('task-2', 1, 'FULL_ANALYSIS', 'ASR', 'goal', 'input', 'model', 'SUCCESS')
                    """);
            statement.executeUpdate("""
                    INSERT INTO analysis_artifacts (
                        media_id, artifact_type, stage, content_hash, content_json
                    ) VALUES (1, 'TRANSCRIPT', 'ASR', 'artifact', '{}')
                    """);
            statement.executeUpdate("""
                    INSERT INTO model_raw_responses (
                        task_id, media_id, api_name, stage, model_version,
                        prompt_hash, response_text, parse_status
                    ) VALUES ('task-2', 1, 'model-api', 'SUMMARY', 'model', 'prompt', '{}', 'SUCCESS')
                    """);
            statement.executeUpdate("""
                    INSERT INTO analysis_quality_evaluations (task_id, media_id, evaluator)
                    VALUES ('task-2', 1, 'quality-v1')
                    """);
            statement.executeUpdate("""
                    INSERT INTO video_qa_conversations (conversation_id, user_id, media_id, title)
                    VALUES ('00000000-0000-0000-0000-000000000003', 1, 1, '测试会话')
                    """);
            statement.executeUpdate("""
                    INSERT INTO video_qa_messages (conversation_id, user_id, media_id, role, content)
                    VALUES ('00000000-0000-0000-0000-000000000003', 1, 1, 'USER', '问题')
                    """);
            statement.executeUpdate("""
                    INSERT INTO rag_segments (
                        user_id, media_id, segment_index, source, content, token_text
                    ) VALUES (1, 1, 0, 'TRANSCRIPT', '片段', '片段')
                    """);

            statement.executeUpdate("DELETE FROM media_files WHERE id = 1");

            assertThat(rowCount(statement, "analysis_tasks")).isZero();
            assertThat(rowCount(statement, "analysis_stage_checkpoints")).isZero();
            assertThat(rowCount(statement, "analysis_artifacts")).isZero();
            assertThat(rowCount(statement, "analysis_quality_evaluations")).isZero();
            assertThat(rowCount(statement, "video_qa_conversations")).isZero();
            assertThat(rowCount(statement, "video_qa_messages")).isZero();
            assertThat(rowCount(statement, "rag_segments")).isZero();
            assertThat(rowCount(statement, "model_raw_responses")).isEqualTo(1);
            try (ResultSet result = statement.executeQuery("SELECT media_id FROM model_raw_responses")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("media_id")).isNull();
            }
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

package com.ruoyi.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SessionMigrationTest {
    @Test
    void v1MigrationContainsTheCompleteSessionBaseline() throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/migration/V1__initialize_session_schema.sql")) {
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(migration.contains("migrated from RuoYi-Cloud/sql/init-platform-and-session.sql"));
            assertEquals(13, migration.split("CREATE TABLE IF NOT EXISTS", -1).length - 1);
            assertTrue(migration.contains("`s_principal`"));
            assertTrue(migration.contains("`s_api_idempotency`"));
        }
    }
}

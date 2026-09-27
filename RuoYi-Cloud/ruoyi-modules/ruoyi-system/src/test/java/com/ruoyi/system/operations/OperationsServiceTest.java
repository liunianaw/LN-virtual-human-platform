package com.ruoyi.system.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.system.asset.service.IAvatarProductionService;

class OperationsServiceTest
{
    @Test
    void callListMapsDatabaseColumnsToApiFieldNames()
    {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:operations;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table p_call_record (id bigint primary key, account_id bigint not null, "
            + "operation_key varchar(128), capability varchar(32), status varchar(32), provider_code varchar(64), "
            + "model_id varchar(128), provider_request_id varchar(128), input_tokens bigint, output_tokens bigint, input_chars bigint, image_count bigint, "
            + "audio_duration_ms bigint, usage_available boolean, cost_amount decimal(18,6), currency varchar(3), "
            + "cost_source varchar(32), error_code varchar(64), created_at timestamp, updated_at timestamp, "
            + "session_id bigint, turn_id bigint)");
        jdbc.execute("create table p_call_review (id bigint, call_id bigint, actor_id bigint, reviewed_status varchar(32), "
            + "evidence_note varchar(1000), cost_amount decimal(18,6), currency varchar(3), cost_source varchar(32), created_at timestamp)");
        jdbc.update("insert into p_call_record (id,account_id,operation_key,capability,status,usage_available,cost_source,created_at,updated_at) "
            + "values (?,?,?,?,?,?,?,current_timestamp,current_timestamp)", 71L, 9L, "generation:81", "GENERATION", "FAILED", false, "UNKNOWN");
        jdbc.update("insert into p_call_record (id,account_id,operation_key,capability,status,input_tokens,output_tokens,usage_available,cost_source,created_at,updated_at) "
            + "values (?,?,?,?,?,?,?,?,?,current_timestamp,current_timestamp)",
            72L, 9L, "llm:82", "LLM", "SUCCEEDED", 123L, 45L, true, "UNKNOWN");
        OperationsService service = new OperationsService(jdbc, mock(IAvatarProductionService.class));

        Map<String, Object> page = service.calls(null, "LLM", null, null, null, null, null, 1, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertEquals(1, items.size());
        assertEquals("72", items.get(0).get("callId"));
        assertEquals("9", items.get(0).get("accountId"));
        assertEquals(123L, items.get(0).get("inputTokens"));
        assertEquals(45L, service.call(72L).get("outputTokens"));
        assertNotNull(items.get(0).get("etag"));
    }
}

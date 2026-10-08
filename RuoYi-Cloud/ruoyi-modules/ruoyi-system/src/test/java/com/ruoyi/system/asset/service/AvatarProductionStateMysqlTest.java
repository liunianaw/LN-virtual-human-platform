package com.ruoyi.system.asset.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.system.asset.mapper.AvatarProductionMapper;

@EnabledIfEnvironmentVariable(named = "AVATAR_TEST_MYSQL_URL", matches = ".+")
class AvatarProductionStateMysqlTest
{
    @Test
    void replacementResultUnblocksAssemblyWithoutErasingUnknownHistory() throws Exception
    {
        String url = System.getenv("AVATAR_TEST_MYSQL_URL");
        String user = System.getenv("AVATAR_TEST_MYSQL_USER");
        String password = System.getenv("AVATAR_TEST_MYSQL_PASSWORD");
        String schema = "ln_avatar_state_test_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate server = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        server.execute("create database `" + schema + "`");
        try {
            var dataSource = new DriverManagerDataSource(url + schema + "?serverTimezone=UTC", user, password);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table p_generation_task (id bigint primary key, account_id bigint, avatar_version_id bigint, official_service_id bigint)");
            jdbc.execute("create table p_generation_step (id bigint primary key, task_id bigint, account_id bigint, action_code varchar(24), "
                + "status varchar(24), step_type varchar(32), error_code varchar(64), next_run_at datetime, stage_started_at datetime, "
                + "reserved_attempt_id bigint, attempt_no int, result_metadata json, created_at datetime)");
            jdbc.execute("create table p_generation_attempt (id bigint primary key, step_id bigint, attempt_no int, provider_task_id varchar(64))");
            jdbc.execute("create table p_official_service (id bigint primary key, status varchar(24), secret_id bigint, capability varchar(32))");
            jdbc.execute("create table p_secret (id bigint primary key, status varchar(24))");
            jdbc.execute("create table p_avatar_action_selection (account_id bigint, avatar_version_id bigint, latest_attempt_id bigint, selection_closed int)");
            jdbc.execute("insert into p_generation_task values (10,1,100,null),(11,1,100,null),(12,2,100,null),(13,1,200,null)");
            jdbc.execute("insert into p_generation_step (id,task_id,account_id,action_code,status,step_type,error_code,reserved_attempt_id) values "
                + "(10,10,1,'nod','UNKNOWN','GENERATE_ACTION','UPSTREAM_RESULT_UNKNOWN',10),"
                + "(11,11,1,'nod','SUCCEEDED','GENERATE_ACTION','UPSTREAM_RESULT_UNKNOWN',11),"
                + "(12,12,2,'nod','SUCCEEDED','GENERATE_ACTION',null,12),"
                + "(13,13,1,'nod','SUCCEEDED','GENERATE_ACTION',null,13)");
            jdbc.execute("insert into p_avatar_action_selection values (1,100,11,1)");
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new ClassPathResource("mapper/system/AvatarProductionMapper.xml"));
            var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AvatarProductionMapper.class);

            assertEquals(0, mapper.countAssemblyBlockers(1L, 100L));
            var cards = mapper.steps(1L, 100L);
            assertEquals(1, cards.size());
            assertEquals("11", cards.get(0).get("latestAttemptId"));
            assertEquals("SUCCEEDED", cards.get(0).get("stage"));
            assertNull(cards.get(0).get("errorCode"));
            assertEquals("UNKNOWN", jdbc.queryForObject("select status from p_generation_step where id=10", String.class));

            jdbc.update("update p_generation_step set status='UNKNOWN' where id=11");
            assertEquals(1, mapper.countAssemblyBlockers(1L, 100L));
            assertEquals("UPSTREAM_RESULT_UNKNOWN", mapper.steps(1L, 100L).get(0).get("errorCode"));
            jdbc.update("update p_generation_step set status='SUCCEEDED' where id=11");
            for (String stage : new String[] {"READY", "RUNNING", "POLLING"}) {
                jdbc.update("update p_generation_step set status=? where id=10", stage);
                assertEquals(1, mapper.countAssemblyBlockers(1L, 100L));
            }
            jdbc.update("update p_generation_step set status='UNKNOWN' where id=10");
            jdbc.update("update p_avatar_action_selection set selection_closed=0");
            assertEquals(1, mapper.countAssemblyBlockers(1L, 100L));
        } finally {
            server.execute("drop database `" + schema + "`");
        }
    }
}

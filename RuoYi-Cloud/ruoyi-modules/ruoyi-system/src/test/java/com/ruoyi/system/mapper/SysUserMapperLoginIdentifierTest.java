package com.ruoyi.system.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.api.domain.SysDept;
import com.ruoyi.system.api.domain.SysRole;
import com.ruoyi.system.api.domain.SysUser;

class SysUserMapperLoginIdentifierTest
{
    private static final String USER_NAME = "m1-user";

    private static final String EMAIL = "m1-user@example.test";

    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception
    {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sys_user_login_identifier;MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, dept_id BIGINT, user_name VARCHAR(30), nick_name VARCHAR(30), email VARCHAR(50), avatar VARCHAR(100), phonenumber VARCHAR(20), password VARCHAR(100), sex CHAR(1), status CHAR(1), del_flag CHAR(1), login_ip VARCHAR(128), login_date TIMESTAMP, pwd_update_date TIMESTAMP, create_by VARCHAR(64), create_time TIMESTAMP, update_by VARCHAR(64), update_time TIMESTAMP, remark VARCHAR(500))");
            statement.execute("CREATE TABLE sys_dept (dept_id BIGINT PRIMARY KEY, parent_id BIGINT, ancestors VARCHAR(500), dept_name VARCHAR(50), order_num INT, leader VARCHAR(20), status CHAR(1))");
            statement.execute("CREATE TABLE sys_user_role (user_id BIGINT, role_id BIGINT)");
            statement.execute("CREATE TABLE sys_role (role_id BIGINT PRIMARY KEY, role_name VARCHAR(30), role_key VARCHAR(100), role_sort INT, data_scope CHAR(1), status CHAR(1))");
            statement.execute("INSERT INTO sys_user (user_id, user_name, nick_name, email, status, del_flag) VALUES (1, 'm1-user', 'M1 User', 'm1-user@example.test', '0', '0')");
        }

        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("SysUser", SysUser.class);
        configuration.getTypeAliasRegistry().registerAlias("SysRole", SysRole.class);
        configuration.getTypeAliasRegistry().registerAlias("SysDept", SysDept.class);
        try (InputStream mapper = Resources.getResourceAsStream("mapper/system/SysUserMapper.xml"))
        {
            new XMLMapperBuilder(mapper, configuration, "mapper/system/SysUserMapper.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void resolvesTheSameActiveUserByUsernameAndEmail()
    {
        try (SqlSession session = sqlSessionFactory.openSession())
        {
            SysUserMapper mapper = session.getMapper(SysUserMapper.class);
            SysUser userByName = mapper.selectUserByUserName(USER_NAME);
            SysUser userByEmail = mapper.selectUserByUserName(EMAIL);

            assertNotNull(userByName);
            assertNotNull(userByEmail);
            assertEquals(userByName.getUserId(), userByEmail.getUserId());
            assertEquals(USER_NAME, userByEmail.getUserName());
        }
    }
}

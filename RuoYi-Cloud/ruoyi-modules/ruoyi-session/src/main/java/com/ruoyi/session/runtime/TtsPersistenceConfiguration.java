package com.ruoyi.session.runtime;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Configuration
@MapperScan(basePackages = "com.ruoyi.session.runtime.mapper", sqlSessionFactoryRef = "ttsSqlSessionFactory")
public class TtsPersistenceConfiguration
{
    @Bean
    public SqlSessionFactory ttsSqlSessionFactory(DataSource dataSource) throws Exception
    {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/runtime/*.xml"));
        return factory.getObject();
    }
}

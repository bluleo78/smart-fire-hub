package com.smartfirehub.global.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

@Configuration
public class PipelineSandboxDataSourceConfig {

  // --- 메인 DataSource + DSLContext (app 사용자) ---
  // 파이프라인 전용 빈을 추가하면 Spring Boot auto-config의
  // @ConditionalOnMissingBean이 비활성화되므로 명시적으로 선언한다.

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  public DataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean
  @Primary
  public DSLContext dslContext(DataSource dataSource) {
    // Spring Boot auto-config의 동작을 재현:
    // 1. TransactionAwareDataSourceProxy → Spring @Transactional과 jOOQ가 같은 커넥션 공유
    // 2. SpringTransactionProvider → dsl.transaction()이 Spring 트랜잭션에 참여 (테스트 롤백 정상 동작)
    DefaultConfiguration config = new DefaultConfiguration();
    config.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    config.set(SQLDialect.POSTGRES);
    config.set(new SpringTransactionProvider(new DataSourceTransactionManager(dataSource)));
    return DSL.using(config);
  }

  // --- 파이프라인 샌드박스 DataSource + DSLContext (pipeline_executor 사용자) ---

  @Bean("pipelineDataSource")
  public DataSource pipelineDataSource(
      @Value("${app.pipeline.datasource.url}") String url,
      @Value("${app.pipeline.datasource.username}") String username,
      @Value("${app.pipeline.datasource.password}") String password) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(url);
    ds.setUsername(username);
    ds.setPassword(password);
    ds.setMaximumPoolSize(10);
    ds.setPoolName("pipeline-sandbox-pool");
    ds.setConnectionTestQuery("SELECT 1");
    return ds;
  }

  @Bean("pipelineDslContext")
  public DSLContext pipelineDslContext(@Qualifier("pipelineDataSource") DataSource dataSource) {
    return DSL.using(new DataSourceConnectionProvider(dataSource), SQLDialect.POSTGRES);
  }
}

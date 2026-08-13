package com.smartfirehub.global.config;

import com.smartfirehub.global.tenant.TenantAwareTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jooq.ExceptionTranslatorExecuteListener;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PipelineSandboxDataSourceConfig {

  // --- 메인 DataSource + DSLContext (app_tenant 사용자) ---
  // V83/Task 7 이후 메인 커넥션은 RLS 강제를 위해 비특권 롤인 app_tenant 로 접속한다.
  // 파이프라인 전용 빈을 추가하면 Spring Boot auto-config의
  // @ConditionalOnMissingBean이 비활성화되므로 명시적으로 선언한다.

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  public DataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  /**
   * 기본 트랜잭션 매니저를 TenantAware 로 교체해, 트랜잭션 시작 시 활성 테넌트를 RLS GUC 로 주입한다.
   * TenantContext 가 비면 GUC 를 설정하지 않으므로 테넌트 무관 동작(마이그레이션·부팅 등)은 그대로다.
   */
  @Bean
  @Primary
  public PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new TenantAwareTransactionManager(dataSource);
  }

  @Bean
  @Primary
  public DSLContext dslContext(DataSource dataSource, PlatformTransactionManager transactionManager) {
    // Spring Boot auto-config의 동작을 재현:
    // 1. TransactionAwareDataSourceProxy → Spring @Transactional과 jOOQ가 같은 커넥션 공유
    // 2. SpringTransactionProvider → dsl.transaction()이 Spring 트랜잭션에 참여 (테스트 롤백 정상 동작)
    //    주의: 여기에 별도 DataSourceTransactionManager 를 새로 만들면 dsl.transaction(...) 호출처가
    //    TenantAware 가 아닌 매니저를 타서 GUC 를 못 받고 RLS 에 전부 막힌다. @Primary 빈을 재사용한다.
    // 3. ExceptionTranslatorExecuteListener → jOOQ 예외를 Spring DataAccessException 계층으로 번역
    DefaultConfiguration config = new DefaultConfiguration();
    config.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    config.set(SQLDialect.POSTGRES);
    config.set(new SpringTransactionProvider(transactionManager));
    config.set(new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT));
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
    // HikariCP는 기본적으로 풀 생성 시 커넥션을 즉시 검증해 실패하면 앱 기동 자체를 막는다.
    // pipeline_executor 비밀번호는 RolePasswordSyncCallback이 Flyway migrate 직후 동기화하므로,
    // 그 시점 이전에 이 빈이 즉시 검증하면 동기화 전 상태로 실패할 수 있다 — 지연 검증으로 전환.
    ds.setInitializationFailTimeout(-1);
    return ds;
  }

  @Bean("pipelineDslContext")
  public DSLContext pipelineDslContext(@Qualifier("pipelineDataSource") DataSource dataSource) {
    // 주 dslContext()와 동일한 패턴 적용:
    // 1. TransactionAwareDataSourceProxy → Spring @Transactional과 jOOQ가 같은 커넥션 공유
    // 2. SpringTransactionProvider → dsl.transaction()이 Spring 트랜잭션에 참여 (테스트 롤백 정상 동작)
    // 3. ExceptionTranslatorExecuteListener → jOOQ 예외를 Spring DataAccessException 계층으로 번역
    // 별도의 DataSourceTransactionManager 를 새로 만드는 것은 의도적이다: 파이프라인 샌드박스(data
    // 스키마)는 이후 단계에서 GUC/RLS 가 아니라 스키마+롤 분리로 격리되므로, 이 커넥션은 테넌트 GUC 주입이
    // 필요 없다("고려했지만 불필요"임을 명시해 다음에 읽는 사람이 "고려 안 됨"으로 오해하지 않게 한다).
    DefaultConfiguration config = new DefaultConfiguration();
    config.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    config.set(SQLDialect.POSTGRES);
    config.set(new SpringTransactionProvider(new DataSourceTransactionManager(dataSource)));
    config.set(new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT));
    return DSL.using(config);
  }
}

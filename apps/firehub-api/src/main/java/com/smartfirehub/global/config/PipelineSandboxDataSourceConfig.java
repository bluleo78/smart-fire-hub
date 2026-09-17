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
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jooq.ExceptionTranslatorExecuteListener;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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

  // 공용 pipeline_executor 자격증명으로 여는 DataSource·DSLContext 는 삭제했다(빈 이름
  // "pipelineDataSource"/"pipelineDslContext").
  //
  // 그 빈들의 마지막 프로덕션 소비자는 PipelineAsyncRunner 의 SQL 컬럼 probe 였다. P3-b2 가 스키마를
  // data_t{id} 로 분리하면서 실행 경로는 테넌트 롤로 옮겼지만 probe 는 이 공용 롤에 남았고, 테넌트 2의
  // 파이프라인이 "permission denied for schema data_t2" 로 터졌다. probe 는 SqlColumnProbe 로 옮겨
  // TenantPipelineDataSourceRegistry(테넌트별 롤)를 쓴다.
  //
  // 빈을 남겨 두고 주석으로 "쓰지 말라"고 적지 않는 이유는 TenantPipelineDataSourceRegistry 가
  // dslForWithoutLease 를 private 으로 둔 것과 같다 — 이 저장소에서 주석형 처방이 낡거나 무시된 전례가
  // 여러 번 있었다. 빈이 없으면 @Qualifier("pipelineDslContext") 주입은 컴파일되지 않으므로, 같은 종류의
  // 실수를 다시 할 수 없다.
  //
  // app.pipeline.datasource.* 프로퍼티 자체는 남는다 — TenantPipelineDataSourceRegistry 와
  // PythonScriptExecutor 가 url 을, FlywayCallbackConfig 가 password 를 계속 읽는다.
  //
  // 공용 롤 **자격증명**(username/password)을 실행 경로에서 읽는 곳은 이제 없다(#681). 예전에는
  // PythonScriptExecutor 가 빈이 아니라 @Value 로 같은 자격증명을 직접 읽어, "공용 롤에 프로덕션
  // 소비자가 없다"는 위 서술이 빈 기준으로만 참이었다 — 그 비대칭을 닫았다. 남은 password 소비자는
  // Flyway 콜백의 ALTER ROLE 동기화 하나이며, 그것은 접속이 아니라 레거시 롤 비밀번호 유지용이다.
}

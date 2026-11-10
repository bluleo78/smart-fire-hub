package com.smartfirehub.global.datastore;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jooq.ExceptionTranslatorExecuteListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 분리 모드({@code firehub.data-store.postgres.separate=true}) 데이터 저장소 빈 배선(WD-494).
 *
 * <p>왜: Tibero 플랫폼 DB 설치본은 사용자 데이터 테이블을 따로 둔 PG 데이터 저장소에 둔다. 그래서 {@code @DataStoreDb} 빈은 플랫폼
 * {@code @Primary} 와 다른 Hikari 풀·DSLContext 여야 한다. 기본 모드의 별칭 빈({@link DataStoreDbConfig})과는 같은
 * 프로퍼티의 반대 값으로 갈라져 둘 중 하나만 생긴다.
 *
 * <p>트랜잭션 매니저는 새로 만들지 않는다 — 연결은 {@link DataStoreConnectionBinding} 이 플랫폼 Spring 트랜잭션에 묶는다(스펙 2절).
 */
@Configuration
@ConditionalOnProperty(name = DataStoreDbConfig.SEPARATE_PROPERTY, havingValue = "true")
public class SeparateDataStoreDbConfig {

  /** 런타임 롤 이름 — {@code TenantSchemaProvisioner.RUNTIME_ROLE} 이 이 이름에 GRANT 하므로 고정한다(설정 키 없음). */
  public static final String RUNTIME_USERNAME = "app_tenant";

  /** 풀 이름 — 지표·로그에서 플랫폼 풀과 구분한다. */
  static final String POOL_NAME = "data-store";

  /**
   * 데이터 저장소 런타임 풀. {@code @Primary} 가 아니므로 한정자 없는 DataSource 주입은 계속 플랫폼 풀로 간다. 소멸 메서드는 추론(close)에
   * 맡겨 컨텍스트 종료 시 이 풀을 닫는다.
   *
   * <p>{@code @DependsOn}: 데이터 저장소 Flyway(기준선 + 롤 비밀번호 동기화)가 끝난 뒤에 만든다 — 그 전에는 {@code app_tenant}
   * 롤· {@code data} 스키마가 없거나 비밀번호가 설정값과 다르다(WD-494 Task 4). DataSource 를 직접 받는 빈도 있으므로 DSLContext 가
   * 아니라 여기에 건다.
   */
  @Bean(name = "dataStoreDataSource")
  @DataStoreDb
  @DependsOn(DataStoreFlywayConfig.BEAN_NAME)
  public DataSource dataStoreDataSource(
      @Value("${firehub.data-store.postgres.url}") String url,
      @Value("${firehub.data-store.postgres.password}") String password,
      @Value("${firehub.data-store.postgres.maximum-pool-size}") int maximumPoolSize,
      @Value("${firehub.data-store.postgres.connection-timeout}") long connectionTimeoutMs,
      @Value("${firehub.data-store.postgres.minimum-idle}") int minimumIdle,
      @Value("${firehub.data-store.postgres.idle-timeout}") long idleTimeoutMs) {
    HikariDataSource ds = new HikariDataSource();
    ds.setPoolName(POOL_NAME);
    ds.setJdbcUrl(url);
    ds.setUsername(RUNTIME_USERNAME);
    ds.setPassword(password);
    // 풀 크기·대기 시간을 설정으로 연다. 바인딩은 Spring 트랜잭션 하나가 끝날 때까지 연결 하나를 쥐고, REQUIRES_NEW 안쪽 트랜잭션은
    // 바깥 것을 쥔 채 하나를 더 쥔다 — 동시 트랜잭션 수가 곧 필요한 연결 수라 플랫폼 풀과 따로 조정해야 한다. 기본값은 Hikari 기본(10개,
    // 30초)으로 지금 동작과 같고, 기본값은 application.yml 한 곳에만 둔다(여기에 :기본값을 다시 적지 않는다).
    ds.setMaximumPoolSize(maximumPoolSize);
    ds.setConnectionTimeout(connectionTimeoutMs);
    // 유휴 연결 수·유휴 만료도 설정으로 연다(WD-704). Hikari 는 minimumIdle 을 안 정하면 최대 크기만큼 유휴 연결을 계속 쥔다 — 스프링
    // 컨텍스트를 여럿 캐시하는 테스트 실행에서는 컨텍스트마다 10개씩 쌓여 데이터 저장소 서버의 연결 한도를 채웠다. 기본값(최대 크기와 같음,
    // 600000ms)은 Hikari 기본이라 운영 동작은 그대로다.
    ds.setMinimumIdle(minimumIdle);
    ds.setIdleTimeout(idleTimeoutMs);
    return ds;
  }

  /**
   * 데이터 저장소 DSLContext. 방언·예외 번역은 플랫폼 {@code @Primary} DSLContext 와 같게
   * 두고(PipelineSandboxDataSourceConfig), 연결 제공자·트랜잭션 제공자만 바인딩 규칙으로 바꾼다.
   */
  @Bean(name = "dataStoreDslContext")
  @DataStoreDb
  @DependsOn(DataStoreFlywayConfig.BEAN_NAME)
  public DSLContext dataStoreDslContext(@DataStoreDb DataSource dataStoreDataSource) {
    DataStoreConnectionBinding binding = new DataStoreConnectionBinding(dataStoreDataSource);
    DefaultConfiguration config = new DefaultConfiguration();
    config.set(binding);
    config.set(SQLDialect.POSTGRES);
    config.set(new DataStoreTransactionProvider(binding));
    config.set(new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT));
    return DSL.using(config);
  }
}

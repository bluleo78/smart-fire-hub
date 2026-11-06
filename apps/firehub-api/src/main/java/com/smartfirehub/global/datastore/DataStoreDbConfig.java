package com.smartfirehub.global.datastore;

import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 데이터 저장소 DataSource·DSLContext 빈 배선.
 *
 * <p>기본 모드(분리 안 함, PG 설치본)에서는 {@code @Primary} 빈을 그대로 돌려주는 별칭만 둔다 — 두 번째 풀을 만들면 PG 설치본의 풀 크기·트랜잭션
 * 경계가 바뀌므로 반드시 같은 인스턴스여야 한다. 분리 모드 빈은 {@link SeparateDataStoreDbConfig}({@code
 * havingValue="true"}).
 */
@Configuration
public class DataStoreDbConfig {

  /** 분리 모드 스위치 프로퍼티 이름. */
  public static final String SEPARATE_PROPERTY = "firehub.data-store.postgres.separate";

  /**
   * 별칭 DataSource — 매개변수 {@code dataSource} 는 {@code @Primary} 빈으로 해석된다. {@code destroyMethod =
   * ""}: 같은 Hikari 인스턴스라 추론된 close 가 종료 때 두 번 불리지 않게 한다(풀의 수명은 {@code @Primary} 빈이 맡는다).
   */
  @Bean(name = "dataStoreDataSource", destroyMethod = "")
  @DataStoreDb
  @ConditionalOnProperty(name = SEPARATE_PROPERTY, havingValue = "false", matchIfMissing = true)
  public DataSource dataStoreDataSource(DataSource dataSource) {
    return dataSource;
  }

  /** 별칭 DSLContext — {@code @Primary} DSLContext(TenantAware 트랜잭션 매니저 연결)를 그대로 돌려준다. */
  @Bean(name = "dataStoreDslContext", destroyMethod = "")
  @DataStoreDb
  @ConditionalOnProperty(name = SEPARATE_PROPERTY, havingValue = "false", matchIfMissing = true)
  public DSLContext dataStoreDslContext(DSLContext dslContext) {
    return dslContext;
  }
}

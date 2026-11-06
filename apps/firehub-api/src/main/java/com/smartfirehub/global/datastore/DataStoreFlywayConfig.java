package com.smartfirehub.global.datastore;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 분리 모드 데이터 저장소 DB 의 두 번째 Flyway(WD-494 스펙 4절).
 *
 * <p>왜: PG 설치본은 플랫폼 Flyway 가 데이터 저장소 객체(data 스키마·확장·롤·권한·fh_* 함수)도 만든다. 분리 모드에서는 데이터 저장소가 다른 DB 라 그
 * 객체를 기준선({@code db/datastore-postgres/V1__datastore_baseline.sql})으로 따로 만든다. PG 설치본({@code
 * separate=false})에는 이 설정 자체가 없다.
 *
 * <p>순서(빈 의존으로 고정): 플랫폼 Flyway({@code flywayInitializer}) → 이 Flyway(migrate + 롤 비밀번호 동기화) → 데이터
 * 저장소 풀 ({@code dataStoreDataSource} 가 {@value #BEAN_NAME} 에 의존). 플랫폼 뒤에 두는 이유는 동기화가 플랫폼 {@code
 * tenant} 를 리포지토리로 읽기 때문이다.
 *
 * <p><b>빈 타입이 {@link Flyway} 가 아닌 이유.</b> 스프링 부트 Flyway 자동 설정은 "Flyway 타입 빈이 없을 때만" ({@code
 * ConditionalOnMissingBean}) 켜지므로, {@code Flyway} 타입 빈을 하나라도 두면 플랫폼 Flyway·{@code
 * flywayInitializer} 가 통째로 빠진다(분리 모드 = Tibero 플랫폼의 마이그레이션이 조용히 멈춘다). 그래서 감싼 타입 {@link
 * DataStoreFlyway} 로 둔다. {@code Flyway.configure()} 로 따로 만들어 플랫폼 {@code
 * FlywayConfigurationCustomizer} (플랫폼 {@code tenant} 를 읽는 콜백)도 섞이지 않는다.
 *
 * <p>연결: 데이터 저장소 소유자 키({@code firehub.data-store.postgres.owner-*})로 Flyway 가 직접 연다. 소유자 풀
 * (SchemaOwnerDataSourceConfig)은 크기 1인데 Flyway 는 PG 에서 연결을 둘 쓰므로(잠금·메타 연결 + 마이그레이션 연결) 그 풀로는 연결 대기에
 * 걸린다.
 */
@Configuration
@ConditionalOnProperty(name = DataStoreDbConfig.SEPARATE_PROPERTY, havingValue = "true")
public class DataStoreFlywayConfig {

  /** 빈 이름 — 데이터 저장소 풀이 {@code @DependsOn} 으로 가리킨다. */
  public static final String BEAN_NAME = "dataStoreFlyway";

  /** 기준선 위치. 플랫폼 위치({@code db/migration})와 겹치지 않는다. */
  public static final String LOCATION = "classpath:db/datastore-postgres";

  /**
   * 데이터 저장소 Flyway 를 만들고 곧바로 migrate 한다. 빈 DB 만 지원하므로(스펙 4절) {@code baselineOnMigrate=false} — 이력
   * 없는 비어 있지 않은 DB 를 만나면 조용히 기준선을 건너뛰지 않고 실패한다. 이력 테이블 이름은 기본({@code flyway_schema_history}).
   */
  @Bean(name = BEAN_NAME)
  @DependsOn("flywayInitializer")
  public DataStoreFlyway dataStoreFlyway(
      @Value("${firehub.data-store.postgres.owner-url}") String url,
      @Value("${firehub.data-store.postgres.owner-username}") String username,
      @Value("${firehub.data-store.postgres.owner-password}") String password,
      DataStoreRolePasswordSync rolePasswordSync) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, username, password)
            .locations(LOCATION)
            .baselineOnMigrate(false)
            .callbacks(rolePasswordSync.callback())
            .load();
    DataStoreFlyway dataStoreFlyway = new DataStoreFlyway(flyway);
    dataStoreFlyway.migrate();
    return dataStoreFlyway;
  }

  /** 데이터 저장소 Flyway 를 감싼 빈 타입 — 위 클래스 Javadoc 의 이유로 {@link Flyway} 를 빈 타입으로 드러내지 않는다. */
  public static final class DataStoreFlyway {

    private final Flyway flyway;

    DataStoreFlyway(Flyway flyway) {
      this.flyway = flyway;
    }

    /**
     * migrate 를 실행한다. 대기 중 마이그레이션이 없어도 AFTER_MIGRATE 롤 비밀번호 동기화는 매번 돈다 — 기동 외에 다시 부를 일은 시크릿 회전 확인·
     * 테스트뿐이다.
     */
    public MigrateResult migrate() {
      return flyway.migrate();
    }
  }
}

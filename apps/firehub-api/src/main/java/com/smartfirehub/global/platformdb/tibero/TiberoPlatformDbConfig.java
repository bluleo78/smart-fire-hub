package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.tibero.flyway.TiberoTenantMigration;
import javax.sql.DataSource;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.spring.autoconfigure.JobRunrProperties;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.oracle.OracleStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tibero 플랫폼 DB 부트스트랩 배선(WD-496) — {@code firehub.platform-db.type=tibero} 일 때만 켜진다.
 *
 * <p>여기서 다루는 것: JobRunr 저장소, 테넌트 격리 Flyway 배선(WD-492). 접속 정보·풀 크기·세션 초기화 문장·Flyway 위치는 {@code
 * application-tibero.yml} 이 정한다 (풀이 만들어지는 자리의 설정이라 이미 시작된 풀·Flyway 내부 연결에도 똑같이 걸린다). UTC 검사는 {@code
 * PlatformDbStartupGuard} 가 기동 맨 앞에서 한다.
 *
 * <p>PG 데이터 저장소 연결 분리는 WD-494 에서 끝났다({@code firehub.data-store.postgres.separate=true}, 프로필 설정은
 * application-tibero.yml). Tibero 마이그레이션 폴더(WD-490)도 있다.
 *
 * <p>잠금({@code DBMS_LOCK})·아웃박스 깨우기({@code DBMS_ALERT})는 WD-493 에서, 테넌트 격리(컨텍스트 + {@code DBMS_RLS},
 * 세션은 {@link TiberoPlatformTenantSession})는 WD-492 에서 끝났다. 남은 것: 리포지토리 MyBatis 구현(WD-491). 그래서 지금은
 * 이 설정만으로 앱 전체가 Tibero 위에서 뜨지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTiberoPlatformDb
public class TiberoPlatformDbConfig {

  private static final Logger log = LoggerFactory.getLogger(TiberoPlatformDbConfig.class);

  /**
   * Flyway 에 테넌트 격리 자리표시자(컨텍스트 이름·소유자 스키마)와 런타임 계정 권한 부여 콜백을 단다(WD-492, {@link
   * TiberoTenantMigration#configure}).
   *
   * <p>소유자 = {@code firehub.platform-db.tibero.owner-schema}(기본 Flyway 계정), 런타임 = 앱 풀 계정({@code
   * spring.datasource.username}). 둘이 같으면 단일 계정 모드라 기동 때 WARN 한 줄을 남긴다 — 앱이 소유자로 붙어 정책에 걸리지 않기 때문이다.
   * PG 의 {@code FlywayCallbackConfig} 는 PG 플랫폼 DB 에서만 켜지므로 커스터마이저가 겹치지 않는다(그래도 {@code configure} 는
   * 기존 콜백·자리표시자를 지우지 않고 더한다).
   */
  @Bean
  FlywayConfigurationCustomizer tiberoTenantMigrationCustomizer(
      @Value("${firehub.platform-db.tibero.owner-schema}") String ownerSchema,
      @Value("${spring.datasource.username}") String runtimeUser) {
    if (TiberoTenantMigration.isSingleAccount(ownerSchema, runtimeUser)) {
      log.warn(
          "런타임 계정이 없어 테넌트 격리가 꺼져 있습니다 — spring.datasource.username 을 소유자와 다른 런타임 계정으로"
              + " 두세요(ADR 0001 「Tibero 테넌트 격리」)");
    }
    return configuration ->
        TiberoTenantMigration.configure(configuration, ownerSchema, runtimeUser);
  }

  /**
   * JobRunr 저장소를 Oracle 구현으로 고정한다. JobRunr 의 SQL 자동 설정은 DB 제품명으로 구현을 고르는데 "Tibero" 는 모르므로 직접 둔다(자동
   * 설정은 같은 타입 빈이 있으면 물러난다). 테이블은 Flyway 가 만든다(skip-create, WD-490 기준선) — 런타임 계정에 DDL 권한을 주지 않는다.
   *
   * <p>자동 설정과 같은 설정 키를 따른다: {@code org.jobrunr.database.datasource}(이름으로 고른 DataSource, 없으면 기본),
   * {@code table-prefix}, {@code skip-create}. Oracle 구현이 Tibero 에서 등록 → 실패 → 재시도 → 성공 한 바퀴를 도는 것은
   * WD-488 실서버 PoC 로 확인했다(UTC JVM 전제).
   */
  @Bean(destroyMethod = "close")
  StorageProvider storageProvider(
      BeanFactory beanFactory, JobMapper jobMapper, JobRunrProperties properties) {
    JobRunrProperties.Database db = properties.getDatabase();
    DataSource dataSource =
        db.getDatasource() == null
            ? beanFactory.getBean(DataSource.class)
            : beanFactory.getBean(db.getDatasource(), DataSource.class);
    DatabaseOptions options =
        db.isSkipCreate() ? DatabaseOptions.SKIP_CREATE : DatabaseOptions.CREATE;
    OracleStorageProvider storage =
        new OracleStorageProvider(dataSource, db.getTablePrefix(), options);
    storage.setJobMapper(jobMapper);
    return storage;
  }
}

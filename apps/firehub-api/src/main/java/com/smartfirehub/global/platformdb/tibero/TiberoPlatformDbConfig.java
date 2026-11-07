package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import javax.sql.DataSource;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.spring.autoconfigure.JobRunrProperties;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.oracle.OracleStorageProvider;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tibero 플랫폼 DB 부트스트랩 배선(WD-496) — {@code firehub.platform-db.type=tibero} 일 때만 켜진다.
 *
 * <p>여기서 다루는 것: JobRunr 저장소. 접속 정보·풀 크기·세션 초기화 문장·Flyway 위치는 {@code application-tibero.yml} 이 정한다
 * (풀이 만들어지는 자리의 설정이라 이미 시작된 풀·Flyway 내부 연결에도 똑같이 걸린다). UTC 검사는 {@code PlatformDbStartupGuard} 가 기동
 * 맨 앞에서 한다.
 *
 * <p>PG 데이터 저장소 연결 분리는 WD-494 에서 끝났다({@code firehub.data-store.postgres.separate=true}, 프로필 설정은
 * application-tibero.yml). Tibero 마이그레이션 폴더(WD-490)도 있다.
 *
 * <p>잠금({@code DBMS_LOCK})·아웃박스 깨우기({@code DBMS_ALERT})는 WD-493 에서 끝났다. 남은 것: 테넌트 격리
 * 세션(WD-492)·리포지토리 MyBatis 구현(WD-491). 그래서 지금은 이 설정만으로 앱 전체가 Tibero 위에서 뜨지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTiberoPlatformDb
public class TiberoPlatformDbConfig {

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

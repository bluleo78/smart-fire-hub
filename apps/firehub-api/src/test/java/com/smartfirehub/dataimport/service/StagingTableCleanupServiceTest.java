package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * StagingTableCleanupService 통합 테스트.
 *
 * <p>고아 staging 테이블 정리 로직과 "활성 JobRunr 작업 게이트"를 검증한다. 모든 테스트는 {@code @Transactional}로 감싸져
 * DDL/DML 변경(테이블 생성·삭제, jobrunr_jobs 조작)이 테스트 종료 시 롤백된다 — PostgreSQL은 트랜잭션 DDL을 지원하므로 안전하다.
 * 게이트는 전역 jobrunr_jobs 상태를 보므로, 각 테스트는 트랜잭션 내에서 활성 상태 행을 먼저 삭제해(다른 테스트가 남긴
 * ENQUEUED 잔여 행의 간섭 제거) 게이트 조건을 결정적으로 만든다.
 */
@Transactional
class StagingTableCleanupServiceTest extends IntegrationTestBase {

  @Autowired private StagingTableCleanupService cleanupService;
  @Autowired private DSLContext dsl;

  /** 생성 규칙(stg_import_ + 32자리 hex)을 만족하는 결정적 staging 테이블명. */
  private static final String STAGING_TABLE = "stg_import_0123456789abcdef0123456789abcdef";

  @BeforeEach
  void clearActiveJobs() {
    // 다른 테스트가 커밋으로 남긴 활성(ENQUEUED 등) jobrunr 행을 트랜잭션 내에서 제거 — 롤백되므로 실제 DB엔 영향 없음.
    dsl.execute(
        "DELETE FROM jobrunr_jobs WHERE state IN ('SCHEDULED', 'ENQUEUED', 'PROCESSING')");
  }

  /** stg_import_ + 32 hex 형태의 물리 테이블을 data 스키마에 생성한다(고아 시뮬레이션). */
  private void createStagingTable(String name) {
    dsl.execute("CREATE TABLE data.\"" + name + "\" (_seq BIGSERIAL, a TEXT)");
  }

  /** data 스키마에 해당 테이블이 존재하는지 확인. */
  private boolean tableExists(String name) {
    Long count =
        dsl.fetchOne(
                "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'data' AND table_name = ?",
                name)
            .get(0, Long.class);
    return count != null && count > 0;
  }

  /** 활성 JobRunr 작업 하나를 PROCESSING 상태로 삽입한다. */
  private void insertActiveJob() {
    dsl.execute(
        "INSERT INTO jobrunr_jobs "
            + "(id, version, jobasjson, jobsignature, state, createdat, updatedat) "
            + "VALUES (?, 0, '{}', 'test-sig', 'PROCESSING', now(), now())",
        "123e4567-e89b-12d3-a456-426614174000");
  }

  @Test
  void 활성작업이_없으면_고아_staging_테이블을_삭제한다() {
    createStagingTable(STAGING_TABLE);
    assertThat(tableExists(STAGING_TABLE)).isTrue();

    int dropped = cleanupService.sweepOrphanedStagingTables();

    assertThat(dropped).isGreaterThanOrEqualTo(1);
    assertThat(tableExists(STAGING_TABLE)).isFalse();
  }

  @Test
  void 활성작업이_있으면_정리를_건너뛴다() {
    createStagingTable(STAGING_TABLE);
    insertActiveJob();

    int dropped = cleanupService.sweepOrphanedStagingTables();

    assertThat(dropped).isZero();
    // 살아있는 임포트가 점유 중일 수 있으므로 테이블은 보존되어야 한다.
    assertThat(tableExists(STAGING_TABLE)).isTrue();
  }

  @Test
  void staging_형식이_아닌_테이블은_건드리지_않는다() {
    // stg_import_ 접두사로 시작하지만 32 hex 규칙에 맞지 않는 사용자 테이블 — 정규식이 걸러내야 한다.
    String userTable = "stg_import_my_report";
    dsl.execute("CREATE TABLE data.\"" + userTable + "\" (_seq BIGSERIAL, a TEXT)");
    try {
      // 활성 작업이 없어 sweep이 실제 고아를 삭제할 수는 있으나, 형식 불일치 테이블은 대상이 아니어야 한다.
      cleanupService.sweepOrphanedStagingTables();

      assertThat(tableExists(userTable)).isTrue();
    } finally {
      dsl.execute("DROP TABLE IF EXISTS data.\"" + userTable + "\"");
    }
  }
}

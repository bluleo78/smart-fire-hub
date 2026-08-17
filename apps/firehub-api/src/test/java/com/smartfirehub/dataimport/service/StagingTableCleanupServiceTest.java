package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

  /**
   * DROP 호출 <b>사이</b>에 개입하기 위한 스파이. 기본 동작은 실제 메서드 위임이라 다른 테스트는
   * 영향을 받지 않고, 스텁은 필요한 테스트 안에서만 설치한다.
   */
  @MockitoSpyBean private DataTableRowService dataTableRowService;

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
    insertActiveJob("123e4567-e89b-12d3-a456-426614174000");
  }

  /**
   * id 를 지정해 활성(PROCESSING) JobRunr 작업을 삽입한다. {@code jobrunr_jobs} 는 tenant_id 가 없는
   * <b>전역 공유 테이블</b>이므로 id 는 테스트마다 달라야 하고(같은 트랜잭션 안에서 두 번 심으면 PK
   * 충돌), 이 클래스의 {@code @Transactional} 롤백으로 반드시 사라져야 한다 — 커밋된 PROCESSING 행
   * 하나가 다른 세션의 이 스윕을 전부 무동작으로 만든다.
   */
  private void insertActiveJob(String jobId) {
    dsl.execute(
        "INSERT INTO jobrunr_jobs "
            + "(id, version, jobasjson, jobsignature, state, createdat, updatedat) "
            + "VALUES (?, 0, '{}', 'test-sig', 'PROCESSING', now(), now())",
        jobId);
  }

  /** 활성(대기/예약/실행 중) JobRunr 작업 수 — 게이트 조건이 실제로 성립했는지 확인용. */
  private long countActiveJobs() {
    return dsl.fetchOne(
            "SELECT count(*) FROM jobrunr_jobs WHERE state IN"
                + " ('SCHEDULED', 'ENQUEUED', 'PROCESSING')")
        .get(0, Long.class);
  }

  /** ACTIVE 테넌트 수 — 순회가 두 번 이상 돌 수 있는지(테스트가 공허하지 않은지) 확인용. */
  private long countActiveTenants() {
    return dsl.fetchOne("SELECT count(*) FROM tenant WHERE status = 'ACTIVE'").get(0, Long.class);
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

  /**
   * <b>순회 도중</b> 임포트가 시작되면 그 이후 테넌트는 스윕하지 않는다(테넌트별 게이트 재확인).
   *
   * <p>왜 이 테스트가 필요한가: 진입 시 게이트를 1회만 보면 "게이트 통과 → 카탈로그 조회" 창이
   * 테넌트 수만큼 늘어난다. 오늘 모든 테넌트가 같은 물리 스키마({@code data})를 공유하므로, 루프
   * 도중 시작된 임포트의 <b>살아있는</b> staging 테이블이 뒤쪽 테넌트의 조회에 잡혀 DROP 되고 그
   * 임포트가 깨진다. 이 결함은 관측이 어렵다 — 정상 경로에서는 아무 로그도 남지 않으므로
   * {@code sweepActiveTenants(true)} 를 {@code false} 로 되돌려도 기존 테스트가 전부 초록이었다.
   *
   * <p>어떻게 재현하나: 첫 테넌트의 DROP 이 일어나는 <b>정확히 그 순간</b>에 (a) 새 staging 테이블을
   * 만들고 (b) PROCESSING 작업 행을 심는다 — 즉 "루프 중간에 임포트가 시작됐다". 게이트 재확인이
   * 있으면 두 번째 테넌트부터는 건너뛰므로 그 테이블이 살아남고, 없으면 DROP 돼 단언이 깨진다.
   *
   * <p>부수효과(스파이 스텁·물리 테이블·jobrunr 행)는 모두 클래스 레벨 {@code @Transactional} 의
   * 롤백으로 사라진다. jobrunr 행은 특히 중요하다 — 커밋되면 공유 테스트 DB 를 쓰는 다른 세션의
   * 스윕이 전부 무동작이 된다.
   */
  @Test
  @DisplayName("순회 도중 임포트가 시작되면 그 살아있는 staging 테이블은 보존된다")
  void 순회_도중_시작된_임포트의_staging_테이블은_보존된다() {
    // 테넌트가 하나뿐이면 "두 번째 테넌트가 건너뛰는가" 를 물을 수 없다 — 공허한 통과를 막는다.
    TenantRlsTestSupport.createActiveTenant(dsl, "sweep-gate-a");
    TenantRlsTestSupport.createActiveTenant(dsl, "sweep-gate-b");
    assertThat(countActiveTenants()).as("순회가 두 번 이상 돌아야 이 테스트가 의미를 가진다").isGreaterThanOrEqualTo(2);
    assertThat(countActiveJobs()).as("@BeforeEach 가 게이트를 열어 둬야 첫 테넌트가 실제로 스윕한다").isZero();

    // 첫 테넌트가 회수할 고아 — 이 DROP 이 개입 지점이 된다.
    createStagingTable(STAGING_TABLE);
    // 루프 도중 시작된 임포트가 만든 "살아있는" staging 테이블(정규식을 만족해야 대상이 된다).
    String liveTable = "stg_import_" + UUID.randomUUID().toString().replace("-", "");

    AtomicInteger injections = new AtomicInteger();
    // 개입 시점에 테이블이 정말 생겼는지를 <b>그 자리에서</b> 기록한다. 스윕이 끝난 뒤에 확인하면
    // "애초에 만들어지지 않았다" 와 "두 번째 테넌트가 DROP 했다" 가 구분되지 않아, 배선 실패가
    // 가드 실패로 오독된다(실제로 그렇게 오독되는 실패 메시지를 한 번 만들어 봤다).
    AtomicBoolean createdAtInjection = new AtomicBoolean();
    Mockito.doAnswer(
            invocation -> {
              if (injections.incrementAndGet() == 1) {
                createStagingTable(liveTable);
                createdAtInjection.set(tableExists(liveTable));
                insertActiveJob("0f0f0f0f-1111-4222-8333-444455556666");
              }
              return invocation.callRealMethod();
            })
        .when(dataTableRowService)
        .dropStagingTable(anyString());

    int dropped = cleanupService.sweepOrphanedStagingTables();

    // 개입이 실제로 일어났는지 먼저 본다 — 스파이가 배선되지 않으면 아래 단언이 "가드가 동작했다" 로
    // 오해될 수 있다(테이블이 애초에 만들어지지 않아 존재하지 않는 것과 구분되지 않는다).
    assertThat(injections.get()).as("스파이가 DROP 을 가로채지 못했다 — 배선 확인 필요").isGreaterThanOrEqualTo(1);
    assertThat(createdAtInjection.get()).as("개입 시점에 살아있는 staging 테이블이 만들어져야 한다").isTrue();
    // 공유 테스트 DB 라 다른 세션이 커밋한 활성 행이 도중에 보일 수 있으므로 "1 이상" 으로 본다.
    assertThat(countActiveJobs())
        .as("루프 중간에 심은 PROCESSING 행이 보여야 게이트가 닫힌다")
        .isGreaterThanOrEqualTo(1);

    // 첫 테넌트는 게이트가 열린 상태로 진입했으므로 고아를 회수한다.
    assertThat(dropped).as("첫 테넌트의 스윕은 실제로 일어나야 한다").isGreaterThanOrEqualTo(1);
    assertThat(tableExists(STAGING_TABLE)).as("고아는 회수돼야 한다").isFalse();
    // 핵심 단언: 테넌트별 게이트 재확인이 없으면 두 번째 테넌트가 이것을 DROP 한다.
    assertThat(tableExists(liveTable))
        .as("순회 도중 시작된 임포트의 살아있는 staging 테이블이 DROP 됐다 — 그 임포트가 깨진다")
        .isTrue();
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

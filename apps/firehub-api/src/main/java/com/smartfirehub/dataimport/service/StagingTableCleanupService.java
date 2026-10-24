package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대용량 임포트 스트리밍 중 생성되는 staging 테이블({@code data.stg_import_<uuid>})의 고아(orphan) 정리 서비스.
 *
 * <p>정상 경로에서는 {@code DataImportService.processImport}의 finally 블록이 staging 테이블을 DROP한다. 그러나 JobRunr
 * 워커가 임포트 도중 프로세스 강제 종료(파드 evict / OOM kill / 노드 장애 등)되면 finally가 실행되지 못해 staging 테이블이 DB에 영구
 * 잔존한다(디스크 낭비). 이 서비스가 주기적으로 그 고아 테이블을 회수한다. staging 테이블명은 어디에도 영속화되지 않으므로 이름으로 추적할 수 없고, {@code
 * data} 스키마의 물리 테이블을 직접 열거해 판별한다.
 *
 * <p><b>안전 게이트(가장 중요):</b> staging 테이블은 오직 {@code processImport} 실행 중에만 존재하며, 그 동안 해당 JobRunr 작업은
 * 활성 상태(SCHEDULED / ENQUEUED / PROCESSING)로 남아 있다. 따라서 <b>활성 작업이 하나도 없을 때에 한해</b> 현존하는 모든 {@code
 * stg_import_%} 테이블을 "고아로 확정"하고 DROP한다. 활성 작업이 하나라도 있으면(임포트가 아닌 다른 종류의 작업이라도) 이번 주기는 통째로 건너뛴다. 이
 * 보수성은 의도된 설계다:
 *
 * <ul>
 *   <li>거짓 음성(진행 중 임포트를 "없음"으로 오판) → 살아있는 staging 테이블을 DROP → 진행 중 임포트 손상(치명적).
 *   <li>과보수(굳이 건너뛸 필요 없는 정리를 건너뜀) → 고아가 다음 한산한 주기까지 남을 뿐(무해, 다음에 회수됨).
 * </ul>
 *
 * 두 리스크는 비대칭이므로 항상 안전한 쪽(과보수)을 택한다. 작업 종류 판별을 위해 {@code jobrunr_jobs}의 JSON을 문자열 매칭하지 않는 이유도 이것이다 —
 * 매칭 실패가 곧 거짓 음성이 되어 치명적 DROP을 유발할 수 있으므로, 종류를 가리지 않고 상태(state)만으로 게이트한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StagingTableCleanupService {

  private final DSLContext dsl;
  private final DataTableRowService dataTableRowService;
  // @Scheduled 경로에는 승계할 테넌트가 없다 — ACTIVE 테넌트를 순회해야 스키마명을 파생할 수 있다.
  private final TenantScopedRunner tenantScopedRunner;
  // 테넌트별 카탈로그 조회 구간의 트랜잭션 경계(아래 findStagingTables 주석 참고).
  private final TransactionTemplate transactionTemplate;
  // 외부 저장소(Tibero 등) 테넌트 목록 — 그 테넌트의 staging 은 PG 가 아니라 외부 스키마에 있다(WD-74 A8).
  private final TenantDataStoreRepository tenantDataStoreRepository;
  // 백엔드별 행 저장소 — 외부 백엔드의 고아 staging 정리를 그 구현에 맡긴다(구체 구현에 의존하지 않는다).
  private final DataStoreResolver dataStoreResolver;
  // 외부 저장소 스윕의 테넌트별 추가 게이트 — Tibero 파이프라인 스테이징은 JobRunr 게이트에 잡히지 않는다(아래 sweepExternalTenants).
  private final PipelineExecutionRepository pipelineExecutionRepository;

  /**
   * 외부 저장소 고아 staging 으로 볼 최소 나이({@code app.data-store.staging-min-age}, 기본 1시간). PG 스윕에는 나이 기준이
   * 없고(활성 작업 게이트만 있다) 외부 스윕도 같은 게이트 뒤에서만 돈다 — 이 나이는 그 위에 얹은 이중 안전장치다: 게이트 확인과 DROP 사이에 막 시작된 임포트의
   * staging 은 만든 지 몇 초라 대상이 되지 않는다.
   */
  @Value("${app.data-store.staging-min-age:PT1H}")
  private final Duration stagingMinAge;

  /** JobRunr 작업이 "활성"으로 간주되는 상태 집합(대기/예약/실행 중). 이 중 하나라도 있으면 정리를 건너뛴다. */
  private static final String ACTIVE_JOB_STATES = "('SCHEDULED', 'ENQUEUED', 'PROCESSING')";

  /**
   * 30분마다 고아 staging 테이블을 정리한다. 부팅 직후 초기화 잡음을 피하려 5분 지연 후 첫 실행한다. 스케줄 예외가 다음 주기를 막지 않도록 sweep 실패는
   * 로깅 후 삼킨다(고아 정리는 긴급하지 않으므로 다음 주기에 재시도된다).
   *
   * <p><b>원 HTTP 요청이 없는 경로라 승계할 테넌트가 없다</b> — 스키마명을 테넌트에서 파생시키게 된 뒤로는 순회가 필수다. 순회하지 않으면 {@code
   * MissingTenantScopeException} 이 아래 catch 에 걸려 경고만 남고 고아 회수가 <b>영구히</b> 무동작이 된다(예외도 실패 테스트도 없는
   * 누수).
   */
  @Scheduled(fixedRate = 1_800_000, initialDelay = 300_000)
  public void scheduledSweep() {
    try {
      sweepOrphanedStagingTables();
    } catch (Exception e) {
      log.warn("고아 staging 테이블 정리 중 오류 — 다음 주기에 재시도", e);
    }
  }

  /**
   * 정리 사이클 1회 = <b>게이트 판단 1회 + ACTIVE 테넌트 순회</b>. 스케줄러와 분리된 메서드로, 테스트가 직접 호출해 게이트 동작을 검증할 수 있다.
   *
   * <p><b>게이트는 진입 시 1회 + 테넌트마다 재확인한다.</b> 순회 전 1회만 묻는 것으로는 부족하다.
   *
   * <p><b>정정(라운드 1 리뷰) — 근거가 뒤집힌 채 결론만 맞아 있었다.</b> 이전 버전의 이 문단은 "오늘 {@code DataSchema.current()} 는
   * 모든 테넌트가 같은 물리 스키마를 공유하므로 남의 테넌트 것도 보인다"를 근거로 들었는데, P3-b2 T1(커밋 {@code 266c002b}) 이후로는 <b>거짓</b>
   * 이다 — {@link #findStagingTables()} 가 {@code table_schema} 도 현재 테넌트에서 파생시켜 조회하므로, 이제 한 테넌트의 스윕이
   * 남의 테넌트 staging 을 볼 수 없다(스키마 자체가 다르다). 그런데도 재확인은 여전히 필요하다 — 남은 위험은 <b>크로스 테넌트</b>가 아니라 <b>같은 테넌트
   * 안의 경합</b>이다. 순회가 테넌트 A 를 처리하는 동안(=A 의 게이트 재확인과 그 뒤의 {@code findStagingTables()}+DROP 사이), A 에 새
   * 임포트가 막 시작되면 그 임포트가 방금 만든 staging 테이블이 여전히 "고아처럼" 보여 DROP 될 수 있다. 순회 전 1회만 확인하면 이 창이 "루프 전체(N개
   * 테넌트 처리 시간)" 로 늘어나고, 테넌트마다 재확인하면 "그 테넌트 하나의 처리 시간" 으로 좁혀진다 — 위험이 사라지는 게 아니라 폭이 줄어드는 것이다. 클래스 주석의
   * "거짓 음성은 치명적 / 과보수는 무해" 비대칭에서 과보수 쪽이다. {@code jobrunr_jobs} 카운트는 인덱스 한 번이라 30분 주기에 N번 묻는 비용은 무시할
   * 수 있다.
   *
   * @return DROP한 테이블 총 개수(활성 작업이 있어 건너뛴 경우 0)
   */
  public int sweepOrphanedStagingTables() {
    if (hasActiveJobs()) {
      // 활성 작업 존재 → 살아있는 임포트가 staging을 점유 중일 수 있으므로 이번 주기는 통째로 건너뛴다.
      log.debug("활성 JobRunr 작업 존재 — 고아 staging 정리 건너뜀");
      return 0;
    }
    int dropped = sweepActiveTenants(true);
    // PG 스윕 뒤 외부 저장소 테넌트를 같은 게이트 아래에서 정리한다.
    return dropped + sweepExternalTenants(true);
  }

  /**
   * 외부 저장소 백엔드(PostgreSQL 이 아니고 행 저장소 구현이 등록된 것 — 현재 Tibero)마다 READY 테넌트를 순회해 오래된 고아 staging 테이블을
   * 지운다(WD-74 A8). 외부 테넌트의 staging 은 {@code data} 스키마가 아니라 테넌트 외부 스키마에 있어 PG 스윕이 보지 못한다. 실제 삭제는 그
   * 백엔드의 {@link DataRowStore#dropStaleStagingTables} 가 맡는다.
   *
   * <p>순회는 PG 스윕과 같은 {@link TenantScopedRunner#forEachTenant} 를 쓴다 — 테넌트마다 컨텍스트를 세우고, 한 테넌트의 실패(연결
   * 불가·권한 등)는 로그만 남기고 다음 테넌트를 계속한다. 게이트 재확인 규칙도 PG 스윕과 같다.
   *
   * <p><b>파이프라인 실행 게이트(최종 리뷰 I1).</b> Tibero 파이프라인 스텝(Python·API_CALL·AI_CLASSIFY 등)도 import 와 같은
   * {@code STG_<24hex>} 이름의 스테이징을 쓴다(db-executor 적재 규칙이 이 접두사를 전제한다). 그런데 파이프라인 실행은 JobRunr 가 아니라
   * {@code @Async} 라 위의 JobRunr 게이트가 보지 못하고, 한 스텝이 스테이징을 1시간 넘게 쥐는 일도 흔하다(LLM 배치·긴 API 페이지·스크립트).
   * 그래서 테넌트마다 <b>RUNNING 파이프라인 실행이 없을 때만</b> 지운다 — 실행 중인 테넌트는 이번 주기를 건너뛰고(다음 주기에 회수), 나머지 테넌트는 그대로
   * 정리한다. 이 게이트는 재확인 플래그와 무관하게 늘 적용한다. 조회가 실패하면 {@code forEachTenant} 가 그 테넌트를 건너뛰므로 실패해도 지우지 않는
   * 쪽이다. PG 스윕({@code stg_import_})에는 적용하지 않는다(PG 파이프라인 임시 테이블은 이름이 달라 대상이 아니다).
   *
   * @param recheckGatePerTenant 테넌트마다 활성 작업 게이트를 재확인할지(프로덕션 경로는 true)
   * @return 지운 staging 테이블 총 개수
   */
  int sweepExternalTenants(boolean recheckGatePerTenant) {
    AtomicInteger total = new AtomicInteger();
    for (DataStoreBackend backend : dataStoreResolver.rowStoreBackends()) {
      if (backend == DataStoreBackend.POSTGRESQL) {
        continue; // PG 는 위의 data 스키마 스윕이 맡는다.
      }
      DataRowStore rowStore = dataStoreResolver.rowStore(backend);
      List<Long> tenantIds = tenantDataStoreRepository.findReadyTenantIds(backend);
      tenantScopedRunner.forEachTenant(
          tenantIds,
          tenantId -> {
            if (recheckGatePerTenant && hasActiveJobs()) {
              log.debug("순회 중 활성 JobRunr 작업 감지 — {} 테넌트 {} 스윕 건너뜀", backend, tenantId);
              return;
            }
            // 실행 중인 파이프라인이 이 테넌트의 STG_ 스테이징을 쥐고 있을 수 있다 — 이번 주기는 이 테넌트만 건너뛴다.
            // forEachTenant 가 테넌트 컨텍스트를 세운 상태라 저장소 트랜잭션이 RLS GUC 를 이 테넌트로 넣는다.
            if (pipelineExecutionRepository.hasRunningExecution(tenantId)) {
              log.debug("실행 중인 파이프라인 존재 — {} 테넌트 {} 스윕 건너뜀", backend, tenantId);
              return;
            }
            int dropped = rowStore.dropStaleStagingTables(tenantId, stagingMinAge);
            if (dropped > 0) {
              log.info("테넌트 {} {} 고아 staging 테이블 {}개 정리 완료", tenantId, backend, dropped);
            }
            total.addAndGet(dropped);
          });
    }
    return total.get();
  }

  /**
   * ACTIVE 테넌트를 순회해 테넌트별 스윕을 실행한다. <b>게이트를 전혀 묻지 않는다</b> — 정책(이번 주기에 스윕해도 되는가)과 기계장치(모든 테넌트를 도는가)를
   * 분리해 두면, 전역 JobRunr 상태에 의존하지 않고 순회 자체를 테스트로 고정할 수 있다({@code CleanupSchedulerTenantTest}). 프로덕션
   * 경로는 이 오버로드가 아니라 {@link #sweepOrphanedStagingTables()} 를 쓴다.
   *
   * @return DROP한 테이블 총 개수
   */
  public int sweepActiveTenants() {
    return sweepActiveTenants(false);
  }

  /**
   * 순회 본체.
   *
   * @param recheckGatePerTenant 테넌트마다 활성 작업 게이트를 재확인할지(프로덕션 경로는 true)
   * @return DROP한 테이블 총 개수
   */
  private int sweepActiveTenants(boolean recheckGatePerTenant) {
    AtomicInteger total = new AtomicInteger();
    AtomicInteger visited = new AtomicInteger();
    tenantScopedRunner.forEachActiveTenant(
        tenantId -> {
          visited.incrementAndGet();
          if (recheckGatePerTenant && hasActiveJobs()) {
            // 루프 도중 임포트가 시작됐다 → 이 테넌트 이후는 건너뛴다(살아있는 staging 보호).
            log.debug("순회 중 활성 JobRunr 작업 감지 — 테넌트 {} 스윕 건너뜀", tenantId);
            return;
          }
          total.addAndGet(sweepCurrentTenant());
        });
    if (visited.get() == 0) {
      // ACTIVE 테넌트가 하나도 없으면 스윕은 무동작이다. 예전에는 무조건 1회 돌았으므로, 이 상태를
      // "정리할 것이 없다"와 구분해 남긴다 — 조용한 영구 무동작이 이 서비스의 알려진 실패 모드다.
      log.warn("ACTIVE 테넌트가 없어 고아 staging 스윕이 무동작 — 테넌트 상태 확인 필요");
    }
    return total.get();
  }

  /**
   * 한 테넌트 범위의 스윕 본문. 호출 시점에 {@code TenantContext} 가 설정돼 있어야 한다 — {@link #findStagingTables()} 가
   * 스키마명을 테넌트에서 파생시키기 때문이다.
   *
   * @return 이 테넌트에서 DROP한 테이블 개수
   */
  private int sweepCurrentTenant() {
    List<String> orphans = findStagingTables();
    if (orphans.isEmpty()) {
      return 0;
    }

    // 활성 작업이 전혀 없으므로 현존하는 staging 테이블은 모두 고아로 확정 → 회수. dropStagingTable은 내부에서
    // validateName + DROP TABLE IF EXISTS를 수행하므로 개별 실패에도 안전하다.
    //
    // 이 루프는 트랜잭션으로 감싸지 않는다 — 감싸면 DROP 들이 한 트랜잭션에 묶여 하나가 실패할 때
    // 나머지가 함께 롤백되고, 바로 위에서 말한 "개별 실패에도 안전하다"는 성질이 사라진다.
    // DROP 은 RLS 대상이 아니라 GUC 도 필요 없다.
    for (String table : orphans) {
      dataTableRowService.dropStagingTable(table);
    }
    log.info("고아 staging 테이블 {}개 정리 완료: {}", orphans.size(), orphans);
    return orphans.size();
  }

  /** {@code jobrunr_jobs}에 대기/예약/실행 중(활성) 작업이 하나라도 있는지 확인한다. */
  private boolean hasActiveJobs() {
    Long count =
        dsl.fetchOne("SELECT count(*) FROM jobrunr_jobs WHERE state IN " + ACTIVE_JOB_STATES)
            .get(0, Long.class);
    return count != null && count > 0;
  }

  /**
   * {@code data} 스키마에서 staging 테이블 목록을 조회한다. LIKE가 아닌 정규식으로 {@code stg_import_} + 정확히 32자리 hex 형태만
   * 매칭해, 우연히 같은 접두사로 시작하는 사용자 테이블을 실수로 삭제하지 않도록 한다.
   */
  private List<String> findStagingTables() {
    // table_schema 도 현재 테넌트에서 파생시킨다. 낡은 리터럴을 남기면 스키마가 분리되는 순간 이
    // 조회가 **예외도 로그도 없이 0행**을 돌려주고, 고아 staging 테이블이 영구히 누적된다 —
    // "정리할 것이 없다"와 구분되지 않으므로 어떤 테스트도 실패하지 않는다.
    //
    // 조회를 트랜잭션으로 감싸는 이유: TenantScopedRunner 는 ThreadLocal 만 세우고 GUC 는
    // TenantAwareTransactionManager.doBegin 에서만 주입된다. information_schema 는 오늘 RLS
    // 대상이 아니라 GUC 가 없어도 동작하지만, 스키마명을 테넌트에서 파생시키는 조회를 트랜잭션
    // 경계 안에 두어야 형제 스케줄러(TriggerEventService.collectRowCounts)와 규약이 같아지고,
    // 배선 회귀를 CleanupSchedulerTenantTest 의 프로브가 판별할 수 있다.
    return transactionTemplate.execute(
        status ->
            dsl.fetch(
                    "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name ~ '^stg_import_[0-9a-f]{32}$'",
                    DataSchema.current())
                .getValues("table_name", String.class));
  }
}

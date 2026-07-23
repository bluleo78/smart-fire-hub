package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataset.service.DataTableRowService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 대용량 임포트 스트리밍 중 생성되는 staging 테이블({@code data.stg_import_<uuid>})의 고아(orphan) 정리 서비스.
 *
 * <p>정상 경로에서는 {@code DataImportService.processImport}의 finally 블록이 staging 테이블을 DROP한다. 그러나 JobRunr 워커가
 * 임포트 도중 프로세스 강제 종료(파드 evict / OOM kill / 노드 장애 등)되면 finally가 실행되지 못해 staging 테이블이 DB에 영구
 * 잔존한다(디스크 낭비). 이 서비스가 주기적으로 그 고아 테이블을 회수한다. staging 테이블명은 어디에도 영속화되지 않으므로 이름으로
 * 추적할 수 없고, {@code data} 스키마의 물리 테이블을 직접 열거해 판별한다.
 *
 * <p><b>안전 게이트(가장 중요):</b> staging 테이블은 오직 {@code processImport} 실행 중에만 존재하며, 그 동안 해당 JobRunr 작업은
 * 활성 상태(SCHEDULED / ENQUEUED / PROCESSING)로 남아 있다. 따라서 <b>활성 작업이 하나도 없을 때에 한해</b> 현존하는 모든
 * {@code stg_import_%} 테이블을 "고아로 확정"하고 DROP한다. 활성 작업이 하나라도 있으면(임포트가 아닌 다른 종류의 작업이라도)
 * 이번 주기는 통째로 건너뛴다. 이 보수성은 의도된 설계다:
 *
 * <ul>
 *   <li>거짓 음성(진행 중 임포트를 "없음"으로 오판) → 살아있는 staging 테이블을 DROP → 진행 중 임포트 손상(치명적).
 *   <li>과보수(굳이 건너뛸 필요 없는 정리를 건너뜀) → 고아가 다음 한산한 주기까지 남을 뿐(무해, 다음에 회수됨).
 * </ul>
 *
 * 두 리스크는 비대칭이므로 항상 안전한 쪽(과보수)을 택한다. 작업 종류 판별을 위해 {@code jobrunr_jobs}의 JSON을 문자열 매칭하지 않는
 * 이유도 이것이다 — 매칭 실패가 곧 거짓 음성이 되어 치명적 DROP을 유발할 수 있으므로, 종류를 가리지 않고 상태(state)만으로 게이트한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StagingTableCleanupService {

  private final DSLContext dsl;
  private final DataTableRowService dataTableRowService;

  /** JobRunr 작업이 "활성"으로 간주되는 상태 집합(대기/예약/실행 중). 이 중 하나라도 있으면 정리를 건너뛴다. */
  private static final String ACTIVE_JOB_STATES = "('SCHEDULED', 'ENQUEUED', 'PROCESSING')";

  /**
   * 30분마다 고아 staging 테이블을 정리한다. 부팅 직후 초기화 잡음을 피하려 5분 지연 후 첫 실행한다. 스케줄 예외가 다음 주기를
   * 막지 않도록 sweep 실패는 로깅 후 삼킨다(고아 정리는 긴급하지 않으므로 다음 주기에 재시도된다).
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
   * 활성 JobRunr 작업이 하나도 없을 때에 한해 현존하는 모든 {@code data.stg_import_<uuid>} 테이블을 DROP한다.
   * 스케줄러와 분리된 순수 메서드로, 단위 테스트가 직접 호출해 게이트 동작을 검증할 수 있다.
   *
   * @return DROP한 테이블 개수(활성 작업이 있어 건너뛴 경우 0)
   */
  public int sweepOrphanedStagingTables() {
    if (hasActiveJobs()) {
      // 활성 작업 존재 → 살아있는 임포트가 staging을 점유 중일 수 있으므로 이번 주기는 통째로 건너뛴다.
      log.debug("활성 JobRunr 작업 존재 — 고아 staging 정리 건너뜀");
      return 0;
    }

    List<String> orphans = findStagingTables();
    if (orphans.isEmpty()) {
      return 0;
    }

    // 활성 작업이 전혀 없으므로 현존하는 staging 테이블은 모두 고아로 확정 → 회수. dropStagingTable은 내부에서
    // validateName + DROP TABLE IF EXISTS를 수행하므로 개별 실패에도 안전하다.
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
   * {@code data} 스키마에서 staging 테이블 목록을 조회한다. LIKE가 아닌 정규식으로 {@code stg_import_} + 정확히 32자리 hex
   * 형태만 매칭해, 우연히 같은 접두사로 시작하는 사용자 테이블을 실수로 삭제하지 않도록 한다.
   */
  private List<String> findStagingTables() {
    return dsl.fetch(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'data' AND table_name ~ '^stg_import_[0-9a-f]{32}$'")
        .getValues("table_name", String.class);
  }
}

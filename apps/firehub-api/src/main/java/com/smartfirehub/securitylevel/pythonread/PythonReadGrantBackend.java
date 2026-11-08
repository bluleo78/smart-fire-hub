package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PYTHON 읽기 슬롯 GRANT 동기화의 백엔드별 구현(PG {@link PythonReadGrantSync}, Tibero {@link
 * TiberoPythonReadGrantSync})이 따르는 계약(WD-78). 파사드 {@link PythonReadGrants} 가 현재 테넌트의 백엔드로 하나를 골라 같은
 * 메서드를 부른다.
 *
 * <p>두 구현이 똑같이 갖는 "데이터셋 → 테이블 찾기" 와 "커밋 후 테이블 재동기화 예약" 은 정적 도우미({@link #syncDatasetTable}, {@link
 * #scheduleTableSync})로 한 번만 둔다 — 한쪽만 고치면 RLS(새 트랜잭션)·실패 시 로그만 남기는 계약이 갈라지기 때문이다.
 */
interface PythonReadGrantBackend {

  /** PYTHON 실행 직전 준비 — 실행 슬롯 번호(1~10). 실패는 {@link PythonReadAccessException}. */
  int prepareForRun(Clearance runAs, int readRank);

  /** 현재 테넌트 전체 재동기화. */
  PythonReadGrantSync.SyncResult syncTenant();

  /** 등급 정의 변경 뒤 전체 재동기화(백엔드별로 실행 중 스크립트를 막는 방법이 다르다). */
  PythonReadGrantSync.SyncResult syncTenantForLevelsChange();

  /** 데이터셋 하나의 테이블만 재동기화. */
  void syncDataset(long datasetId);

  /** 쓰기 경로 훅 — 커밋 후 전용 실행기에서(트랜잭션이 없으면 즉시) 그 테이블만 재동기화. 실패는 로그만. */
  void syncTableAfterCommit(String tableName);

  /**
   * 데이터셋의 테이블 이름을 새 트랜잭션에서 찾아(dataset 은 RLS 테이블이라 트랜잭션=GUC 없이 읽으면 0행이다) 그 테이블을 동기화한다. 물리 테이블이 없는
   * 데이터셋(문서·파일형)은 할 일이 없다.
   */
  static void syncDatasetTable(
      TransactionTemplate requiresNew,
      DatasetRepository datasetRepository,
      long datasetId,
      Consumer<String> syncTable) {
    // 플랫폼 테이블(dataset) 조회는 리포지토리로 — 데이터 저장소 DSL 로 읽으면 분리 설치에서 엉뚱한 DB 를 본다(WD-494).
    String table =
        requiresNew.execute(status -> datasetRepository.findTableNameById(datasetId).orElse(null));
    if (table != null) {
      syncTable.accept(table);
    }
  }

  /**
   * 쓰기 경로 훅 공통부 — 트랜잭션 동기화가 활성이면 커밋된 경우({@code STATUS_COMMITTED})에만 완료 후 전용 실행기에서 비동기로, 아니면 호출
   * 스레드에서 즉시 {@code syncTable} 을 부르고, 실패는 로그만 남긴다(다음 실행 직전·야간 동기화가 회복). 왜 실행기인지는 {@link
   * PythonReadSyncExecutor} 참고.
   *
   * <p>예약 단계 전체가 try 안이다 — 테넌트 컨텍스트 누락(require 실패)·동기화 등록 실패도 "로그만, 커밋을 막지 않는다" 계약에 든다. 미커밋 트랜잭션
   * 안에서 바로 동기화하면 새 트랜잭션이 아직 보이지 않는 데이터셋 행을 못 읽어 "없음" 으로 회수하므로 반드시 커밋 뒤에 돈다.
   *
   * @param executor 커밋 뒤 동기화를 돌릴 전용 실행기(PG·Tibero 공용)
   * @param logPrefix 로그 문구 앞머리(백엔드 구분, 예: {@code ""}·{@code "Tibero "})
   */
  static void scheduleTableSync(
      String tableName,
      Logger log,
      String logPrefix,
      PythonReadSyncExecutor executor,
      Consumer<String> syncTable) {
    try {
      // 테넌트는 예약 시점(요청 스레드)에 잡는다 — 실행기 스레드에는 TenantContext 가 없다.
      long tenantId = TenantContext.require("PYTHON 읽기 권한 동기화 예약");
      Runnable work =
          () -> {
            try {
              // 실행기 스레드에서 테넌트를 다시 세운다. runScoped 는 finally 로 진입 전 값(null)으로 되돌려 다음 작업에 새지 않는다.
              TenantContext.runScoped(tenantId, () -> syncTable.accept(tableName));
            } catch (RuntimeException e) {
              log.warn(
                  "{}PYTHON 읽기 권한 동기화 실패(다음 동기화에서 회복): tenant={} table={}",
                  logPrefix,
                  tenantId,
                  tableName,
                  e);
            }
          };
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                // 여기서 동기로 돌리면 바깥 커넥션을 쥔 채 두 번째 커넥션을 잡는다 — 실행기에 넘기기만 한다(포화·종료면 버림, 예외 없음).
                if (status == STATUS_COMMITTED) {
                  executor.submit(work);
                }
              }
            });
      } else {
        work.run();
      }
    } catch (RuntimeException e) {
      log.warn("{}PYTHON 읽기 권한 동기화 예약 실패(다음 동기화에서 회복): table={}", logPrefix, tableName, e);
    }
  }
}

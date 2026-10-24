package com.smartfirehub.securitylevel.pythonread;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import java.util.function.Consumer;
import org.jooq.DSLContext;
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

  /** 쓰기 경로 훅 — 커밋 후(트랜잭션이 없으면 즉시) 그 테이블만 재동기화. 실패는 로그만. */
  void syncTableAfterCommit(String tableName);

  /**
   * 데이터셋의 테이블 이름을 새 트랜잭션에서 찾아(dataset 은 RLS 테이블이라 트랜잭션=GUC 없이 읽으면 0행이다) 그 테이블을 동기화한다. 물리 테이블이 없는
   * 데이터셋(문서·파일형)은 할 일이 없다.
   */
  static void syncDatasetTable(
      TransactionTemplate requiresNew, DSLContext dsl, long datasetId, Consumer<String> syncTable) {
    String table =
        requiresNew.execute(
            status ->
                dsl.select(DATASET.TABLE_NAME)
                    .from(DATASET)
                    .where(DATASET.ID.eq(datasetId))
                    .fetchOptional(DATASET.TABLE_NAME)
                    .orElse(null));
    if (table != null) {
      syncTable.accept(table);
    }
  }

  /**
   * 쓰기 경로 훅 공통부 — Spring 트랜잭션 동기화가 활성이면 커밋 후, 아니면 즉시 {@code syncTable} 을 부른다. 실패는 로그만 남긴다(판정이 아니라
   * 이중 방어선이라 커밋을 막지 않는다). 다음 실행 직전·야간 동기화가 회복한다.
   *
   * <p>예약 단계 전체가 try 안이다 — 테넌트 컨텍스트 누락(require 실패)·동기화 등록 실패도 "로그만, 커밋을 막지 않는다" 계약에 든다. 미커밋 트랜잭션
   * 안에서 바로 동기화하면 새 트랜잭션이 아직 보이지 않는 데이터셋 행을 못 읽어 "없음" 으로 회수하므로 반드시 커밋 뒤에 돈다.
   *
   * @param logPrefix 로그 문구 앞머리(백엔드 구분, 예: {@code ""}·{@code "Tibero "})
   */
  static void scheduleTableSync(
      String tableName, Logger log, String logPrefix, Consumer<String> syncTable) {
    try {
      long tenantId = TenantContext.require("PYTHON 읽기 권한 동기화 예약");
      Runnable work =
          () -> {
            try {
              // afterCommit 콜백은 다른 컨텍스트에서 돌 수 있어 테넌트를 명시로 다시 세운다.
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
              public void afterCommit() {
                work.run();
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

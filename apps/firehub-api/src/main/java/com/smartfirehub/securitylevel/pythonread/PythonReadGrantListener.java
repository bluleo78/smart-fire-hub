package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 등급 이벤트 → PYTHON 읽기 슬롯 권한 반영(스펙 §4.3, WD-29). 발행은 흐름 B
 * 소유(SecurityLevelService·DatasetSecurityService) — 이 클래스는 구독만 한다.
 *
 * <p><b>보장(WD-624).</b> "읽기 권한 <b>좁히기</b>는 커밋 응답 전에 끝난다" — 단, 요청 하나가 플랫폼 풀 커넥션을 2개 쥐지 않는다. 그래서 일을
 * 셋으로 나눈다.
 *
 * <ol>
 *   <li><b>커밋 직전(BEFORE_COMMIT), 발행 트랜잭션 안에서</b> — 좁히기 중 커넥션이 더 필요 없는 것: Tibero 스크립트 토큰 세대 번호 올리기,
 *       데이터셋 테이블의 남는 권한 회수(REVOKE). 같은 트랜잭션이라 등급 변경과 함께 커밋된다. 가장 높은 우선순위로 돈다(다른 커밋 직전 작업보다 먼저).
 *   <li><b>커밋 직후(AFTER_COMMIT), 호출 스레드에서 상한만큼만</b> — PG 슬롯 롤 세션 끊기. 소유자 풀 전용 스레드에서 돌고 여기서는 짧게
 *       기다린다(플랫폼 풀을 더 쥐지 않고, 소유자 풀이 바빠도 30초씩 멈추지 않는다). 확인 못 하면 미확인 표식 → 실행 준비가 넓히기 전에 먼저 끊는다.
 *   <li><b>전용 실행기(비동기)</b> — 넓어지는 GRANT 와 전체 재계산(새 트랜잭션 = 커넥션 하나 더). 큐 대기만큼 늦어도 과권한 창이 없다: 넓어지기 전에
 *       실행 중 세션은 이미 끊기거나(PG) 토큰이 무효이고(Tibero), 실행기 작업도 넓히기 전에 다시 끊거나 번호를 올린다.
 * </ol>
 *
 * <p><b>게시자 트랜잭션을 절대 롤백시키지 않는다.</b> 등급 변경은 파이프라인 실패 복원·클론·관리자 변경 같은 업무 트랜잭션 안에서 발행된다. 이 구독자의 실패로 그
 * 업무가 되돌아가면 안 되므로, 커밋 직전 작업은 savepoint 안에서 돌고({@link PythonReadGrants}) 이 클래스는 어떤 경우에도 예외를 밖으로 내지
 * 않는다. 좁히기를 확인하지 못하면 대신 <b>막는다</b>(fail-closed 대체 경로, {@link #narrowDatasetBeforeCommit}).
 *
 * <p><b>왜 커밋 콜백에서 백엔드를 묻지 않는가.</b> 백엔드 조회가 캐시를 놓치면 커밋 콜백 스레드가 아직 묶인 바깥 커넥션으로 DB 를 읽는다. 커밋 직전 단계는 정상
 * 트랜잭션이라 물어도 되지만, 토큰 번호 올리기(행이 없는 PG 테넌트는 변화 없음)와 PG 세션 끊기(Tibero 테넌트는 끊을 세션 없음)는 백엔드와 무관하게 늘 해도
 * 안전하므로 묻지 않는다.
 *
 * <p><b>왜 fallbackExecution 을 켜지 않는가.</b> 트랜잭션 없이 발행된 이벤트는 버려지지만(발행 측은 늘 트랜잭션 안에서 발행한다), 실행
 * 직전(JIT)·기동/일 1회 동기화가 회복한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PythonReadGrantListener {

  private final PythonReadGrants sync;

  /** 커밋 뒤 넓히는 동기화를 돌리는 전용 실행기(WD-594 와 같은 것) — 커밋 콜백 스레드가 플랫폼 풀 커넥션 2개를 쥐지 않게 한다(WD-624). */
  private final PythonReadSyncExecutor executor;

  /**
   * 등급 정의 변경 전체 동기화가 실행기 큐에서 <b>아직 시작하지 않은 채</b> 기다리는 테넌트들(WD-624).
   *
   * <p><b>왜 합치는가.</b> 전체 동기화는 테넌트의 모든 테이블·슬롯을 그 시점 계산값에 맞추므로, 큐에서 기다리는 것이 하나 있으면 같은 테넌트의 다음 변경은 그
   * 하나가 시작될 때 함께 반영된다. 순서 변경 화면 등이 등급 변경을 연달아 커밋하면 같은 무거운 작업이 쌓여 단일 스레드 큐를 오래 붙잡는다.
   *
   * <p><b>왜 작업이 시작할 때(맨 첫 문장에서) 지우는가.</b> 작업이 등급을 읽은 뒤(실행 중) 커밋된 변경은 그 작업에 반영되지 않는다. 시작할 때 지우면 실행
   * 중에 커밋된 변경은 표식이 없음을 보고 작업을 하나 더 넣는다 — 마지막 변경이 반드시 반영된다. 끝날 때(finally) 지우면 그 변경이 합쳐진 것으로 잘못 보고
   * 버려진다. 지우기를 테넌트 세우기·동기화보다 <b>먼저</b> 두므로 그 뒤 무엇이 실패해도 표식이 남지 않는다. 넣기가 받아들여지지 않으면(포화·종료·예외)
   * finally 에서 지운다. 남는 예외: 종료 시 {@code shutdownNow} 가 시작 전 작업을 버리면 표식이 남지만, 그때는 실행기가 이미 닫혀 어차피 새
   * 작업을 받지 않는다.
   */
  private final Set<Long> levelsSyncPending = ConcurrentHashMap.newKeySet();

  /**
   * 등급 정의 변경 — 커밋 직전, 발행 트랜잭션에서 스크립트 토큰 세대 번호를 올린다(Tibero 실행 중 스크립트의 옛 토큰을 등급 변경 커밋과 동시에 무효로).
   *
   * <p>실패해도 등급 변경은 커밋한다(게시자를 롤백시키지 않는다) — 파사드가 savepoint 만 되돌리고 ERROR 를 남긴다. 그때는 커밋 뒤 실행기의 전체
   * 동기화({@link TiberoPythonReadGrantSync#syncTenantForLevelsChange})가 넓히는 GRANT 보다 먼저 번호를 올린다.
   */
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void invalidateScriptTokensBeforeLevelsCommit(SecurityLevelsChangedEvent event) {
    try {
      sync.invalidateScriptTokensInCurrentTx(event.tenantId());
    } catch (RuntimeException e) {
      // 파사드가 이미 삼키지만, 예상 밖 예외도 게시자에게 새지 않게 한 번 더 막는다.
      log.error("등급 정의 변경 커밋 전 스크립트 토큰 무효화 실패: tenant={}", event.tenantId(), e);
    }
  }

  /**
   * 등급 정의 변경(추가·수정·삭제·순서) — 커밋 직후 PG 슬롯 롤 세션을 상한 안에서 끊고({@link
   * PythonReadGrantSync#terminateSlotSessionsAfterCommit}), 테넌트 전체 재동기화(넓히는 GRANT 포함)는 실행기에
   * 넘긴다(테넌트당 큐 대기 1개로 합친다).
   *
   * <p>끊기가 상한을 넘거나 실패해도 커밋 결과는 바꾸지 않는다(이미 커밋됐다). 끊기는 계속 돌고, 미확인 표식이 실행 준비의 넓히기를 막으며, 실행기 작업은 넓히기 전에
   * 다시 끊고 실패하면 넓히지 않는다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLevelsChanged(SecurityLevelsChangedEvent event) {
    long tenantId = event.tenantId();
    String logContext = "등급 정의 변경(kind=" + event.kind() + ")";
    terminateAfterCommit(tenantId, PythonReadSlots.ALL_SLOTS, logContext);
    submitLevelsSync(tenantId, logContext);
  }

  /**
   * 데이터셋 하나의 등급 변경 — 커밋 직전, 발행 트랜잭션에서 그 테이블의 남는 슬롯 권한을 회수한다({@link
   * PythonReadGrants#narrowDatasetInCurrentTx}, savepoint 안). 등급을 올린(좁힌) 변경이면 커밋 응답이 나가기 전에 실행 중
   * 세션의 다음 쿼리부터 막힌다.
   *
   * <p><b>회수를 확인하지 못하면(대체 경로, fail-closed).</b> 잠금 대기 초과·회수 실패·예외·분리 설치면 등급 변경은 그대로 커밋하고, <b>그 테이블을
   * 더는 읽으면 안 되는 슬롯만</b> 막는다.
   *
   * <ol>
   *   <li>같은 트랜잭션에서 Tibero 스크립트 토큰 번호를 올린다(테넌트 단위 — 번호가 테넌트별이라 더 좁힐 수 없다. PG 테넌트는 변화 없음).
   *   <li>커밋 직후 그 슬롯들의 PG 세션을 상한 안에서 끊는다(못 끊으면 미확인 표식 → 실행 준비가 먼저 끊는다).
   *   <li>실행기 작업({@link PythonReadGrants#syncDatasetAfterUnconfirmedNarrowing})이 다시 끊고, 잠금 안에서
   *       회수하고, 회수 커밋 뒤 한 번 더 끊는다 — 커밋 직전에 옛 등급으로 준비된 실행이 첫 끊기 뒤에 붙었더라도 막힌다.
   * </ol>
   *
   * 넓어지는 변경이거나 새 테이블에 줄 권한이 원래 없는 경우(클론 등)는 막을 슬롯이 비어 아무것도 끊지 않는다.
   *
   * <p><b>테넌트 확인.</b> 회수 계산은 이 트랜잭션의 RLS 테넌트로 데이터셋·등급을 읽는다. 발행 측은 같은 컨텍스트로 이벤트를 만들므로 늘 같아야 한다 — 다르면
   * 0행을 읽어 아무것도 회수하지 못하고 조용히 넘어가므로, 회수를 시도하지 않고 ERROR 와 함께 이벤트 테넌트의 모든 슬롯을 막는다(롤백하지 않는다).
   */
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void narrowDatasetBeforeCommit(DatasetSecurityLevelChangedEvent event) {
    long tenantId = event.tenantId();
    long datasetId = event.datasetId();
    try {
      Long context = TenantContext.get();
      NarrowResult result;
      if (context == null || context != tenantId) {
        log.error(
            "데이터셋 등급 변경 이벤트의 테넌트({})가 트랜잭션 테넌트({})와 달라 회수할 수 없다 — 모든 슬롯을 막는다(dataset={})",
            tenantId,
            context,
            datasetId);
        result = NarrowResult.unconfirmed(PythonReadSlots.ALL_SLOTS);
      } else {
        result = sync.narrowDatasetInCurrentTx(datasetId, event.fromLevelId(), event.toLevelId());
      }
      if (result.needsFallback()) {
        blockExposedSlots(tenantId, datasetId, result.exposedSlots());
      }
    } catch (RuntimeException e) {
      // 대체 경로 등록까지 실패한 예상 밖 경우 — 게시자를 롤백시키지 않는다. 커밋 뒤 동기화·실행 준비가 회복한다.
      log.error("데이터셋 등급 변경 커밋 전 PYTHON 읽기 권한 처리 실패: tenant={} dataset={}", tenantId, datasetId, e);
    }
  }

  /** 좁히기를 확인 못 한 데이터셋 등급 변경의 대체 경로 — 토큰 무효화(이 트랜잭션) + 커밋 뒤 슬롯 끊기·뒷정리 작업 예약. */
  private void blockExposedSlots(long tenantId, long datasetId, Set<Integer> exposed) {
    log.error(
        "데이터셋 등급 변경의 PYTHON 읽기 권한 회수를 확인하지 못했다 — 해당 슬롯의 실행 중 스크립트를 막는다(tenant={} dataset={} slots={})",
        tenantId,
        datasetId,
        exposed);
    boolean tokensInvalidated = sync.invalidateScriptTokensInCurrentTx(tenantId);
    String logContext = "데이터셋 등급 변경 회수 미확인(dataset=" + datasetId + ")";
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            terminateAfterCommit(tenantId, exposed, logContext);
            executor.submit(
                () ->
                    runScopedLogged(
                        tenantId,
                        () ->
                            sync.syncDatasetAfterUnconfirmedNarrowing(
                                datasetId, exposed, tokensInvalidated),
                        logContext));
          }
        });
  }

  /**
   * 데이터셋 하나의 등급 변경 — 커밋 뒤 그 테이블 재동기화(넓어지는 GRANT 포함)를 실행기에 넘긴다. 좁히기는 커밋 직전에 이미 끝났으므로({@link
   * #narrowDatasetBeforeCommit}) 큐 대기 동안 과권한 창이 없다. <b>새 실행</b>도 실행 준비(JIT)가 같은 잠금 안에서 커밋된 등급으로 자기
   * 슬롯을 맞춘다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDatasetLevelChanged(DatasetSecurityLevelChangedEvent event) {
    long datasetId = event.datasetId();
    long tenantId = event.tenantId();
    executor.submit(
        () ->
            runScopedLogged(
                tenantId,
                () -> sync.syncDataset(datasetId),
                "데이터셋 등급 변경(dataset=" + datasetId + ")"));
  }

  /** 커밋 직후 주어진 PG 슬롯 세션 끊기(상한 있음). 커밋 콜백이 실패하면 안 되므로 예상 밖 예외도 로그로 끝낸다. */
  private void terminateAfterCommit(long tenantId, Set<Integer> slots, String logContext) {
    try {
      sync.terminateSlotSessionsAfterCommit(tenantId, slots);
    } catch (RuntimeException e) {
      log.error("{} 후 PYTHON 슬롯 세션 끊기 요청 실패: tenant={} slots={}", logContext, tenantId, slots, e);
    }
  }

  /** 등급 정의 변경 전체 동기화를 실행기에 넣는다 — 아직 시작하지 않은 같은 테넌트 작업이 있으면 합친다({@link #levelsSyncPending}). */
  private void submitLevelsSync(long tenantId, String logContext) {
    if (!levelsSyncPending.add(tenantId)) {
      return; // 아직 시작하지 않은 전체 동기화가 큐에 있다 — 그 작업이 시작한 뒤 등급을 읽으므로 이 변경까지 반영한다.
    }
    boolean accepted = false;
    try {
      accepted =
          executor.submit(
              () -> {
                // 맨 첫 문장 — 무엇이 실패해도 표식이 남지 않고, 실행 중에 커밋된 변경은 작업을 하나 더 넣는다(필드 주석).
                levelsSyncPending.remove(tenantId);
                runScopedLogged(tenantId, sync::syncTenantForLevelsChange, logContext);
              });
    } finally {
      if (!accepted) {
        levelsSyncPending.remove(tenantId); // 버려졌다 — 다음 변경이 다시 넣을 수 있게
      }
    }
  }

  /**
   * 실행기 스레드에서 이벤트의 테넌트를 다시 세우고(실행기 스레드에는 TenantContext 가 없다) 작업을 돌린다. 실패하면 테넌트·사유를 담아 WARN 만 남긴다
   * (다음 동기화가 회복). 큐 포화·종료로 버려지면 실행기가 버림 WARN 을 남긴다.
   */
  private static void runScopedLogged(long tenantId, Runnable work, String logContext) {
    try {
      TenantContext.runScoped(tenantId, work);
    } catch (RuntimeException e) {
      log.warn("{} 후 PYTHON 읽기 권한 동기화 실패(다음 동기화에서 회복): tenant={}", logContext, tenantId, e);
    }
  }
}

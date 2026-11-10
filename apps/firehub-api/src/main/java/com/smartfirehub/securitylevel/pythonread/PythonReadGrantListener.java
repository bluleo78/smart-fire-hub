package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
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
 *   <li><b>커밋 직후(AFTER_COMMIT), 호출 스레드는 요청만</b> — PG 슬롯 롤 세션 끊기. 소유자 풀 전용 스레드에서 돌고 커밋 콜백은 <b>기다리지
 *       않는다</b>(WD-664 9 — 예전에는 3초까지 기다려, 동시 커밋 N 개가 바깥 플랫폼 커넥션 N 개를 그만큼 쥐었다). 회수 미확인·등급 정의 변경이면 요청
 *       전에 미확인 표식을 달아 실행 준비가 넓히기 전에 먼저 끊고(fail-closed), 확인된 회수면 실제로 회수한 슬롯만 끊는다(WD-664 7). 끊김은 커밋
 *       응답 뒤 몇 초 안에 일어난다(응답 시점 보장 없음 — 설계 결정 Q2 A).
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
   * 실행기 작업 그룹 이름(WD-644 실행기의 합치기 기준). 커밋 뒤 실행기로 넘기는 네 경로를 어떻게 올렸는지(WD-624 경로 → WD-644 설계, 넷째는
   * WD-664 7):
   *
   * <ul>
   *   <li><b>데이터셋 등급 변경 재동기화</b>({@link #onDatasetLevelChanged}) — 커밋 뒤 경로, <b>재시도형</b>(시도 3번 → 묶인
   *       대기). 잦은 경로(자동 상향·클론·실패 복원)라 바쁜 테넌트가 단일 스레드를 붙잡으면 안 되고, 넓히기·재계산뿐이라(좁히기는 커밋 직전에 끝남) 바쁘면
   *       아무것도 하지 않고 다시 해도 안전하다. 테넌트당 작업 하나에 데이터셋 id 를 모아 잠금 한 번에 맞춘다.
   *   <li><b>등급 정의 변경 전체 동기화</b>({@link #onLevelsChanged}) — <b>대기형</b>. 끊기 → 잠금 안 재확인 → 넓히기 순서를 바쁠
   *       때마다 처음부터 되풀이하면 커밋 뒤 정상 시작한 실행까지 거듭 끊는다. 드문 관리 작업이라 실행기 줄을 잠시 막는 쪽을 받아들인다. 테넌트 그룹 합치기가
   *       WD-624 의 "아직 시작 안 한 작업이 있으면 합침" 표식을 대신한다 — 시도는 시작할 때 대기 항목을 비우므로, 실행 중에 커밋된 변경은 다음 회차로
   *       정확히 한 번 더 돈다.
   *   <li><b>회수 미확인 뒷정리</b>({@link #blockExposedSlots}) — <b>대기형</b>. 끊기 → 잠금 안 회수 → 다시 끊기 순서가 보안
   *       계약이라 나누지 않는다. 드문 대체 경로다. 그룹에 데이터셋·슬롯·토큰 여부를 모두 넣어, 같은 요청만 합친다(다른 요청의 인자를 잃지 않게). 분리 설치에서
   *       확인된 회수가 플랫폼보다 먼저 커밋된 경우({@link #cleanUpSeparateNarrowingAfterCommit}, WD-701 3차 리뷰 B1)도
   *       같은 작업을 같은 성격(대기형·회수 필수, 버려지면 미확인 표식)으로 넣는다.
   *   <li><b>확인된 회수 뒤 끊기 재시도</b>({@link #killRevokedSlotsAfterCommit}, WD-664 7) — <b>대기형</b>. 커밋
   *       콜백이 넣은 끊기 요청이 실패했을 때만 들어간다. 끊기를 기다리다 못 끊으면 일시 실패(SessionKillFailed, WD-664 1b)로 끝나 실행기가
   *       재시도 상한까지 다시 한다. 회수 전에 시작한 긴 쿼리를 막는 유일한 방어선이라 포화여도 버리지 않는다(작업이 작고 실패 때만 생긴다). 그래도 버려지면(재시도
   *       소진·영구 실패·종료) 버림 콜백이 그 슬롯에 미확인 표식을 단다(WD-701 3차). 그룹에 슬롯을 넣어 같은 요청만 합친다.
   * </ul>
   *
   * 네 경로 모두 실행기의 실패 분류(일시 실패만 재시도·영구 실패는 ERROR 후 버림)·종료 비우기를 그대로 따른다. 포화 버림은 데이터셋 등급 변경 재동기화만 따른다 —
   * 등급 정의 변경 전체 동기화·회수 미확인 뒷정리·확인된 회수 뒤 끊기 재시도는 회수 필수 작업이라 상한을 넘겨도 받아들인다(WD-664 1a, {@link
   * PythonReadSyncExecutor#submitRetryable(String, String, PythonReadSyncExecutor.Attempt,
   * boolean)}).
   */
  private static final String LEVELS_GROUP = "levels tenant=";

  private static final String DATASET_GROUP = "dataset-level tenant=";

  private static final String NARROW_GROUP = "narrow-cleanup tenant=";

  private static final String KILL_GROUP = "narrow-kill tenant=";

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
   * 등급 정의 변경(추가·수정·삭제·순서) — 커밋 직후 PG 슬롯 롤 세션에 미확인 표식을 달고 끊기를 요청하고({@link
   * PythonReadGrantSync#terminateSlotSessionsAfterCommit}, 기다리지 않음 — WD-664 9), 테넌트 전체 재동기화(넓히는
   * GRANT 포함)는 실행기에 넘긴다(테넌트당 큐 대기 1개로 합친다).
   *
   * <p>끊기가 늦거나 실패해도 커밋 결과는 바꾸지 않는다(이미 커밋됐다). 끊기는 끊기 스레드에서 계속 돌고, 미확인 표식이 실행 준비의 넓히기를 막으며, 실행기 작업은
   * 넓히기 전에 다시 끊고 실패하면 넓히지 않는다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLevelsChanged(SecurityLevelsChangedEvent event) {
    long tenantId = event.tenantId();
    String logContext = "등급 정의 변경(kind=" + event.kind() + ")";
    terminateAfterCommit(tenantId, PythonReadSlots.ALL_SLOTS, true, logContext);
    submitLevelsSync(tenantId, logContext);
  }

  /**
   * 데이터셋 하나의 등급 변경 — 커밋 직전, 발행 트랜잭션에서 그 테이블의 남는 슬롯 권한을 회수한다({@link
   * PythonReadGrants#narrowDatasetInCurrentTx}, savepoint 안). 등급을 올린(좁힌) 변경이면 커밋 응답이 나가기 전에 실행 중
   * 세션의 다음 쿼리부터 막힌다.
   *
   * <p><b>회수를 확인하지 못하면(대체 경로, fail-closed).</b> 잠금 대기 초과·회수 실패·예외·시간 예산 초과(Tibero·분리 설치)면 등급 변경은
   * 그대로 커밋하고, <b>그 테이블을 더는 읽으면 안 되는 슬롯만</b> 막는다. 분리 설치도 이제 커밋 직전에 데이터 저장소에서 회수를 따로 커밋한다(WD-701 3차 —
   * 예전에는 분리 설치면 늘 이 대체 경로였다).
   *
   * <ol>
   *   <li>같은 트랜잭션에서 Tibero 스크립트 토큰 번호를 올린다(테넌트 단위 — 번호가 테넌트별이라 더 좁힐 수 없다. PG 테넌트는 변화 없음).
   *   <li>커밋 직후 그 슬롯들에 미확인 표식을 달고 PG 세션 끊기를 요청한다 — 기다리지 않는다(WD-664 9). 끊기가 확인될 때까지 표식이 그 슬롯의 실행 준비를
   *       막는다(실행 준비가 먼저 끊고, 못 끊으면 거부).
   *   <li>실행기 작업({@link PythonReadGrants#syncDatasetAfterUnconfirmedNarrowing})이 다시 끊고, 잠금 안에서
   *       회수하고, 회수 커밋 뒤 한 번 더 끊는다 — 커밋 직전에 옛 등급으로 준비된 실행이 첫 끊기 뒤에 붙었더라도 막힌다.
   * </ol>
   *
   * 넓어지는(등급을 내린) 변경이면 더는 읽으면 안 되는 슬롯이 비어 대체 경로가 필요 없다. 클론 상속(CLONE_INHERIT)·자동 상향(AUTO_RAISE)은 변경 전
   * 등급({@code from})으로 새 데이터셋이 생성 때 받은 테넌트 기본 등급(자동 상향은 그 출력의 현재 등급)을 싣는다 — 그래서 더 높은 등급으로 옮기면 막을
   * 슬롯이 <b>비지 않는다</b>(WD-664 5). 회수를 확인하지 못했을 때(잠금 대기 초과·회수 실패·Tibero·분리 설치 시간 예산 초과)에만 위 대체 경로로 그
   * 슬롯을 막는다 — 잠금 대기 초과면 그 차이에 지금 실제로 SELECT 를 가진 슬롯을 더해 막는다(ACL 이 이전 등급보다 넓게 어긋난 경우, WD-701 3차).
   * 회수가 확인되면 실제로 회수한 슬롯만 끊으므로, 권한이 아직 없는 새 테이블의 클론 상속·자동 상향은 아무 세션도 끊지 않는다 — 분리 설치가 늘 대체 경로로 가며 그
   * 차이 슬롯 세션을 테넌트 전체에서 끊던 가용성 회귀(WD-664 5)가 이렇게 풀린다.
   *
   * <p><b>롤백되면.</b> 커밋 직전 회수가 플랫폼 트랜잭션과 따로 커밋됐을 수 있어(분리 설치·Tibero) 롤백 완료 때 그 데이터셋 재동기화를 넣는다({@link
   * #resyncIfRolledBack}).
   *
   * <p><b>회수가 확인되면(WD-664 7, 설계 결정 Q2 A).</b> 실제로 회수한 슬롯이 있으면 커밋 뒤 <b>그 슬롯의</b> PG 세션 끊기를
   * 요청한다({@link #killRevokedSlotsAfterCommit}). PG 는 권한을 쿼리 시작 때만 보므로, 회수 전에 시작한 긴 쿼리·서버 쪽 커서는 회수
   * 뒤에도 계속 읽기 때문이다(예전에는 대체 경로만 끊어 두 경로가 어긋났다). 회수할 권한이 원래 없었으면(회수한 슬롯 없음) 아무것도 끊지 않는다. 롤백되면 회수도
   * 되돌려지므로 끊지 않는다(커밋 뒤 콜백).
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
      } else if (result.needsSeparateCleanup()) {
        cleanUpSeparateNarrowingAfterCommit(tenantId, datasetId, result);
      } else if (result.needsKillAfterCommit()) {
        killRevokedSlotsAfterCommit(tenantId, datasetId, result.revokedSlots());
      }
    } catch (RuntimeException e) {
      // 대체 경로 등록까지 실패한 예상 밖 경우 — 게시자를 롤백시키지 않는다. 커밋 뒤 동기화·실행 준비가 회복한다.
      log.error("데이터셋 등급 변경 커밋 전 PYTHON 읽기 권한 처리 실패: tenant={} dataset={}", tenantId, datasetId, e);
    } finally {
      // 좁히기보다 먼저 실패해도(예외) 롤백 재동기화는 늘 건다(WD-701 3차 리뷰 SF4) — 회수가 데이터 저장소에 이미 커밋됐을 수 있다.
      registerResyncIfRolledBack(tenantId, datasetId);
    }
  }

  /** {@link #resyncIfRolledBack} 등록 — 등록 실패도 커밋 직전 리스너 밖으로 내지 않는다(게시자를 롤백시키지 않는다). */
  private void registerResyncIfRolledBack(long tenantId, long datasetId) {
    try {
      resyncIfRolledBack(tenantId, datasetId);
    } catch (RuntimeException e) {
      log.error("데이터셋 등급 변경 롤백 재동기화 등록 실패: tenant={} dataset={}", tenantId, datasetId, e);
    }
  }

  /**
   * 분리 설치에서 확인된 회수가 플랫폼보다 먼저 커밋됐을 때의 커밋 뒤 뒷정리(WD-701 3차 리뷰 B1).
   *
   * <p><b>왜 끊기만으로 부족한가.</b> 테넌트 동기화 잠금이 데이터 저장소 트랜잭션과 함께 플랫폼 커밋 <b>전에</b> 풀린다. 그 틈에 잠금을 기다리던 동기화(실행
   * 준비·전체·실행기)가 아직 커밋 안 된 옛 등급을 읽고 방금 회수한 슬롯에 다시 GRANT 하고, 그 슬롯으로 스크립트를 붙였을 수 있다. 요청만 하는 끊기(표식
   * 없음)로는 그 GRANT 가 남아 다음 쿼리도 통과한다. 그래서 커밋되면 대기형·회수 필수 작업({@link
   * PythonReadGrantSync#syncDatasetAfterSeparateNarrowing}: 잠금 안에서 <b>커밋된 새 등급</b>으로 다시 회수 →
   * 회수한·되살아난 슬롯만 끊기)을 넣는다 — 차이 슬롯을 테넌트 전체에서 끊지 않는다(WD-664 5). 포화여도 버리지 않고, 그래도 버려지면 회수·차이 슬롯에 미확인
   * 표식을 단다(실행 준비가 먼저 끊고 새 등급으로 맞춘다). 작업이 시작되기 전 지연을 줄이려고 회수한 슬롯 끊기 요청도 곧바로 넣는다(실패는 로그만 — 작업이 다시
   * 끊는다). 같은 DB 설치·Tibero 는 잠금이 플랫폼 커밋 때 풀려 이 틈이 없다({@link PythonReadGrantSync} 분리 설치 좁히기 주석).
   *
   * <p><b>받아들인 남은 창(WD-701 3차 재리뷰).</b> 테넌트 잠금을 기다리던 동기화는 데이터 저장소 커밋이 잠금을 푸는 순간 잠금을 잡아, 플랫폼 커밋 전에 옛
   * 등급을 읽고 다시 GRANT 할 수 있다. 그 틈에 붙은 스크립트 — 또는 전에는 권한이 없던 차이 슬롯이라 곧바로 끊는 집합(회수한 슬롯)에 들지 않은 슬롯의 스크립트
   * — 는 이 회수 필수 작업이 돌 때까지(실행기 대기 + 잠금 대기, 보통 1초 미만) 그 테이블을 읽는다. 그 뒤의 실행 준비는 커밋된 새 등급으로 맞추므로 이 창을
   * 늘리지 않는다. 창을 완전히 없애려면 이 뒷정리를 커밋 콜백 안에서 동기로(잠금 대기·끊기 기다림 포함) 해야 하지만, 그러면 등급 변경 응답이 잠금 경합·끊기 상한(최대
   * 수 초)만큼 늦어지고 커밋 콜백이 기다리지 않는다는 규칙(WD-664 9)을 깬다 — 드문 경쟁의 짧은 창을 받아들이고 응답 지연을 피하는 쪽을 골랐다.
   */
  private void cleanUpSeparateNarrowingAfterCommit(
      long tenantId, long datasetId, NarrowResult result) {
    Set<Integer> revoked = result.revokedSlots();
    // 버려지면 표식을 달 슬롯 — 되살아났을 수 있는 차이 슬롯까지(드문 경우라 넓게, fail-closed).
    Set<Integer> slots = new TreeSet<>(revoked);
    slots.addAll(result.exposedSlots());
    String logContext = "데이터셋 등급 변경 분리 설치 회수(dataset=" + datasetId + ")";
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            requestKillQuietly(tenantId, revoked, logContext);
            try {
              executor.submitRetryable(
                  NARROW_GROUP
                      + tenantId
                      + " dataset="
                      + datasetId
                      + " separate revoked="
                      + revoked,
                  String.valueOf(datasetId),
                  PythonReadGrantBackend.waitingAttempt(
                      tenantId,
                      log,
                      logContext + " ",
                      () -> sync.syncDatasetAfterSeparateNarrowing(datasetId, revoked)),
                  // 회수 필수(WD-664 1a) — 옛 등급으로 되살아난 GRANT 를 거두는 유일한 커밋 뒤 방어선이다.
                  true,
                  dropped -> markKillUnconfirmed(tenantId, slots, logContext));
            } catch (RuntimeException e) {
              log.error(
                  "{} 뒤 PYTHON 읽기 회수 뒷정리 등록 실패: tenant={} slots={}",
                  logContext,
                  tenantId,
                  slots,
                  e);
              markKillUnconfirmed(tenantId, slots, logContext);
            }
          }
        });
  }

  /** 슬롯 세션 끊기를 요청만 한다(표식 없음, 기다리지 않음) — 실패는 로그만(뒤따르는 뒷정리 작업이 다시 끊는다). */
  private void requestKillQuietly(long tenantId, Set<Integer> slots, String logContext) {
    if (slots.isEmpty()) {
      return;
    }
    try {
      sync.requestSlotSessionTermination(tenantId, slots)
          .whenComplete(
              (n, e) -> {
                if (e != null) {
                  log.warn(
                      "{} 뒤 PYTHON 슬롯 롤 세션 끊기 요청 실패(뒷정리 작업이 다시 끊는다): tenant={} slots={}",
                      logContext,
                      tenantId,
                      slots,
                      e);
                }
              });
    } catch (RuntimeException e) {
      log.warn(
          "{} 뒤 PYTHON 슬롯 롤 세션 끊기 요청 실패(뒷정리 작업이 다시 끊는다): tenant={} slots={}",
          logContext,
          tenantId,
          slots,
          e);
    }
  }

  /**
   * 발행 트랜잭션이 <b>롤백</b>되면 그 데이터셋 재동기화를 실행기에 넣는다(WD-701 3차) — 커밋 직전 회수가 플랫폼 트랜잭션과 따로 커밋됐을 수 있기 때문이다:
   * 분리 설치는 데이터 저장소에서 플랫폼 커밋 전에 따로 커밋하고, Tibero REVOKE 는 DDL 이라 곧바로 반영된다. 그 뒤 플랫폼이 롤백되면(뒤따르는 커밋 직전
   * 작업·플랫폼 커밋 실패) 등급은 그대로인데 슬롯 권한만 줄어 있다 — 과소권한(fail-closed)이라 안전하지만, 그대로 두면 실행 준비·일 1회 동기화까지 그 슬롯이
   * 읽지 못한다. 재동기화는 롤백된(원래) 등급으로 맞춘다.
   *
   * <p>늘 등록한다(백엔드·설치를 묻지 않는다) — 같은 DB 설치면 회수도 함께 되돌려져 재동기화는 할 일이 없지만, 롤백은 드물어 비용이 작고 분기가 없어 빠뜨릴 수
   * 없다. 완료 콜백은 요청만 넣는다(DB 작업 없음 — 바깥 커넥션을 다시 쓰지 않는다). 넓히기라 포화로 버려져도 보안 공백은 없다(재시도형 일반 작업).
   */
  private void resyncIfRolledBack(long tenantId, long datasetId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              try {
                submitDatasetResync(tenantId, datasetId);
              } catch (RuntimeException e) {
                log.error(
                    "데이터셋 등급 변경 롤백 뒤 PYTHON 읽기 재동기화 등록 실패: tenant={} dataset={}",
                    tenantId,
                    datasetId,
                    e);
              }
            }
          }
        });
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
            terminateAfterCommit(tenantId, exposed, false, logContext);
            // 대기형(필드 주석) — 끊기·회수·다시 끊기를 한 시도 안에서 끝까지 한다.
            executor.submitRetryable(
                NARROW_GROUP
                    + tenantId
                    + " dataset="
                    + datasetId
                    + " slots="
                    + exposed
                    + " tokens="
                    + tokensInvalidated,
                String.valueOf(datasetId),
                PythonReadGrantBackend.waitingAttempt(
                    tenantId,
                    log,
                    logContext + " ",
                    () ->
                        sync.syncDatasetAfterUnconfirmedNarrowing(
                            datasetId, exposed, tokensInvalidated)),
                // 회수 필수(WD-664 1a) — 이 작업이 마지막 회수 방어선이라 포화여도 버리지 않는다.
                true);
          }
        });
  }

  /**
   * 확인된 커밋 직전 회수 뒤 — 커밋되면 실제로 회수한 슬롯의 PG 세션 끊기를 요청한다(WD-664 7). 커밋 콜백은 요청만 넣고 기다리지 않으며(WD-664 9)
   * 표식도 달지 않는다: 회수가 커밋돼 새 실행은 옳은 권한으로 붙으므로 실행 준비를 막을 까닭이 없고, 회수 전에 시작한 쿼리·커서만 끊으면 된다.
   *
   * <p>요청이 실패하면(큐 포화·종료·소유자 풀 오류) 실행기 대기형 작업으로 다시 한다 — 끊기를 기다리다 못 끊으면 일시 실패(SessionKillFailed,
   * WD-664 1b)라 실행기가 재시도 상한까지 다시 한다. 그래도 못 끊어 실행기가 버리면(재시도 소진·영구 실패·포화·종료) 그 슬롯에 미확인 표식을 단다(WD-701
   * 3차, {@link #retryKill}) — 그 슬롯의 다음 실행 준비가 먼저 끊고, 못 끊으면 실행을 거부한다(fail-closed). 예전에는 ERROR 로그만 남아
   * 회수 전에 시작한 긴 쿼리가 끝까지 돌고 새 실행도 아무 확인 없이 붙었다. 첫 요청 실패 때 바로 표식을 달지 않는 이유: 재시도가 곧 끊을 수 있는데, 표식이 있으면
   * 그 사이 회수 뒤 정상으로 시작한 실행까지 실행 준비가 끊는다. 실패 처리는 끊기 스레드(또는 요청이 곧바로 실패하면 커밋 콜백 스레드)에서 실행기에 넣기만 하므로 커밋
   * 콜백을 붙잡지 않는다.
   */
  private void killRevokedSlotsAfterCommit(long tenantId, long datasetId, Set<Integer> revoked) {
    String logContext = "데이터셋 등급 변경 회수 확인(dataset=" + datasetId + ")";
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            CompletableFuture<Integer> kill;
            try {
              kill = sync.requestSlotSessionTermination(tenantId, revoked);
            } catch (RuntimeException e) {
              kill = CompletableFuture.failedFuture(e);
            }
            kill.whenComplete(
                (n, e) -> {
                  if (e != null) {
                    retryKill(tenantId, datasetId, revoked, logContext, e);
                  }
                });
          }
        });
  }

  /**
   * 확인된 회수 뒤 끊기 요청이 실패했을 때 실행기에서 다시 한다(대기형·회수 필수 — 필드 주석). 실행기가 이 작업을 버리면(재시도 소진·영구 실패·포화·종료 — 버림
   * 콜백) 또는 등록 자체가 실패하면 그 슬롯에 미확인 표식을 단다(WD-701 3차, fail-closed). 예외를 밖으로 내지 않는다.
   */
  private void retryKill(
      long tenantId, long datasetId, Set<Integer> slots, String logContext, Throwable cause) {
    try {
      log.warn(
          "{} 뒤 PYTHON 슬롯 롤 세션 끊기 요청 실패 — 실행기에서 다시 한다: tenant={} slots={}",
          logContext,
          tenantId,
          slots,
          cause);
      executor.submitRetryable(
          KILL_GROUP + tenantId + " slots=" + slots,
          String.valueOf(datasetId),
          PythonReadGrantBackend.waitingAttempt(
              tenantId,
              log,
              logContext + " ",
              () -> sync.terminateSlotSessionsOrThrow(tenantId, slots)),
          true,
          dropped -> markKillUnconfirmed(tenantId, slots, logContext));
    } catch (RuntimeException e) {
      log.error(
          "{} 뒤 PYTHON 슬롯 롤 세션 끊기 재시도 등록 실패: tenant={} slots={}", logContext, tenantId, slots, e);
      markKillUnconfirmed(tenantId, slots, logContext);
    }
  }

  /**
   * 확인된 회수 뒤 끊기를 끝내 확인하지 못한 슬롯에 미확인 표식을 단다(WD-701 3차) — 실행 준비가 그 슬롯을 넓히기 전에 먼저 끊고, 못 끊으면 실행을
   * 거부한다({@link PythonReadGrantSync#prepareForRun}). 예외를 밖으로 내지 않는다(실행기 콜백·커밋 콜백에서 불린다).
   */
  private void markKillUnconfirmed(long tenantId, Set<Integer> slots, String logContext) {
    try {
      log.error(
          "{} 뒤 PYTHON 슬롯 롤 세션 끊기를 끝내 확인하지 못했다 — 미확인 표식을 달아 실행 준비가 먼저 끊게 한다: tenant={} slots={}",
          logContext,
          tenantId,
          slots);
      sync.markSlotsUnconfirmed(tenantId, slots);
    } catch (RuntimeException e) {
      log.error("{} 뒤 미확인 표식 달기 실패: tenant={} slots={}", logContext, tenantId, slots, e);
    }
  }

  /**
   * 데이터셋 하나의 등급 변경 — 커밋 뒤 그 테이블 재동기화(넓어지는 GRANT 포함)를 실행기에 넘긴다. 좁히기는 커밋 직전에 이미 끝났으므로({@link
   * #narrowDatasetBeforeCommit}) 큐 대기 동안 과권한 창이 없다. <b>새 실행</b>도 실행 준비(JIT)가 같은 잠금 안에서 커밋된 등급으로 자기
   * 슬롯을 맞춘다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDatasetLevelChanged(DatasetSecurityLevelChangedEvent event) {
    submitDatasetResync(event.tenantId(), event.datasetId());
  }

  /**
   * 데이터셋 하나의 재동기화를 실행기에 넣는다 — 커밋 뒤 경로({@link #onDatasetLevelChanged})와 롤백 뒤 되살리기({@link
   * #resyncIfRolledBack})가 함께 쓴다. 재시도형(필드 주석) — 같은 테넌트의 데이터셋들은 한 작업에 모여 잠금 한 번에 맞춰진다. 백엔드·테이블 찾기는
   * 실행기 스레드에서 한다.
   */
  private void submitDatasetResync(long tenantId, long datasetId) {
    executor.submitRetryable(
        DATASET_GROUP + tenantId,
        String.valueOf(datasetId),
        PythonReadGrantBackend.retryableAttempt(
            tenantId, log, "데이터셋 등급 변경 ", sync::syncDatasetsWith));
  }

  /**
   * 커밋 직후 주어진 PG 슬롯에 미확인 표식을 달고 세션 끊기를 요청한다 — 기다리지 않는다(WD-664 9). 등급 정의 변경이면 {@code
   * slotRedefined}(실행 준비가 그 슬롯 세션 전부를 본다), 데이터셋 회수 미확인이면 아니다(표식 전 세션만, WD-701 4차). 커밋 콜백이 실패하면 안
   * 되므로 예상 밖 예외도 로그로 끝낸다.
   */
  private void terminateAfterCommit(
      long tenantId, Set<Integer> slots, boolean slotRedefined, String logContext) {
    try {
      sync.terminateSlotSessionsAfterCommit(tenantId, slots, slotRedefined);
    } catch (RuntimeException e) {
      log.error("{} 후 PYTHON 슬롯 세션 끊기 요청 실패: tenant={} slots={}", logContext, tenantId, slots, e);
    }
  }

  /**
   * 등급 정의 변경 전체 동기화를 실행기에 넣는다 — 대기형(필드 주석). 같은 테넌트 작업이 이미 있으면 실행기가 합친다: 아직 시작 전이면 그 작업이 이 변경까지
   * 반영하고, 실행 중이면 끝난 뒤 한 번 더 돈다. 회수 필수 작업이라 포화로는 버리지 않고(WD-664 1a), 종료로 버려지면 실행기가 ERROR 와 함께 센다.
   */
  private void submitLevelsSync(long tenantId, String logContext) {
    executor.submitRetryable(
        LEVELS_GROUP + tenantId,
        "levels",
        PythonReadGrantBackend.waitingAttempt(
            tenantId, log, logContext + " ", sync::syncTenantForLevelsChange),
        // 회수 필수(WD-664 1a) — 커밋 직전 토큰 번호 올리기가 실패했을 때의 유일한 대체 경로라 포화여도 버리지 않는다.
        true);
  }
}

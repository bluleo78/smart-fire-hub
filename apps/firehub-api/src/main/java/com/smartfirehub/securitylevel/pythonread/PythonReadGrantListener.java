package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 등급 이벤트 → 커밋 후 PYTHON 읽기 슬롯 GRANT 재동기화(스펙 §4.3, WD-29). 발행은 흐름 B 소유(SecurityLevelService·
 * DatasetSecurityService) — 이 클래스는 구독만 한다.
 *
 * <p><b>왜 AFTER_COMMIT 이고 fallbackExecution 을 켜지 않는가.</b> 미커밋 트랜잭션 안에서 동기화를 돌리면 새 연결이 그 트랜잭션이 잡은
 * 테이블/카탈로그 행을 기다리며 자기 교착할 수 있다. 트랜잭션 없이 발행된 이벤트는 버려지지만, 실행 직전 동기화(JIT)·기동/일 1회 동기화가 회복한다.
 *
 * <p><b>왜 테넌트를 이벤트에서 세우는가.</b> 동기화는 RLS 테이블(security_level·dataset)을 읽는다. 테넌트 컨텍스트가 비거나 다른 테넌트면 0행
 * → "전부 회수"(또는 남의 스키마)가 된다. 실패는 로그만 남긴다 — 판정이 아니라 이중 방어선이라 발행 측 커밋 결과를 바꾸지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PythonReadGrantListener {

  private final PythonReadGrants sync;

  /** 커밋 뒤 동기화를 돌리는 전용 실행기(WD-594 와 같은 것) — 커밋 콜백 스레드가 플랫폼 풀 커넥션 2개를 쥐지 않게 한다(WD-624). */
  private final PythonReadSyncExecutor executor;

  /**
   * 등급 정의 변경 전체 동기화가 실행기 큐에서 <b>아직 시작하지 않은 채</b> 기다리는 테넌트들(WD-624).
   *
   * <p><b>왜 합치는가.</b> 전체 동기화는 테넌트의 모든 테이블·슬롯을 그 시점 계산값에 맞추므로, 큐에서 기다리는 것이 하나 있으면 같은 테넌트의 다음 변경은 그
   * 하나가 시작될 때 함께 반영된다. 순서 변경 화면 등이 등급 변경을 연달아 커밋하면 같은 무거운 작업(세션 끊기 포함)이 쌓여 단일 스레드 큐를 오래 붙잡고, 그 뒤의
   * 테이블 동기화까지 늦어진다.
   *
   * <p><b>왜 끝날 때가 아니라 시작할 때 지우는가.</b> 작업이 이미 등급을 읽은 뒤(실행 중) 들어온 변경은 그 작업에 반영되지 않는다. 시작할 때 표식을 지우면
   * 실행 중에 커밋된 변경이 새 작업을 하나 더 넣으므로 마지막 변경이 반드시 반영된다. 끝날 때 지우면 그 변경이 합쳐진 것으로 잘못 보고 버려진다.
   */
  private final Set<Long> levelsSyncPending = ConcurrentHashMap.newKeySet();

  /**
   * 등급 정의 변경(추가·수정·삭제·순서) — 등급 위치가 바뀌므로 테넌트 전체를 다시 맞추고, 넓어진 GRANT 가 커밋되기 전에 실행 중인 슬롯 롤 세션을 끊는다(실행 중
   * 스크립트가 넓어진 슬롯으로 자격 밖 등급을 읽지 못하게 — {@link PythonReadGrants#syncTenantForLevelsChange()}).
   *
   * <p><b>두 단계로 나눈 이유(WD-624).</b> 예전에는 커밋 콜백에서 전체 동기화(REQUIRES_NEW)를 호출 스레드로 바로 돌렸다. Spring 은 콜백
   * 뒤에야 바깥 커넥션을 반납하므로 요청 하나가 플랫폼 풀 커넥션 2개를 쥐었고, 동시 등급 변경이 풀 크기에 이르면 모두 커넥션 타임아웃(30초)까지 멈춘 뒤 동기화가
   * 실패했다 — 그러면 세션 끊기도 아예 돌지 않았다. 이제:
   *
   * <ol>
   *   <li><b>세션 끊기는 커밋 직후 호출 스레드에서 동기로</b> 한다. 소유자 풀(플랫폼 풀과 다른 풀)만 쓰고, 소유자 커넥션을 쥔 쪽이 플랫폼 커넥션을 기다리는
   *       경로가 없어 순환 대기가 생기지 않는다. 이로써 변경 <b>전에</b> 시작해 이미 접속해 있던 스크립트 세션은 커밋 응답 전에 끊긴다. {@code
   *       pg_terminate_backend} 는 DB 단위라 다른 API 인스턴스가 띄운 세션도 끊는다.
   *   <li><b>넓어지는 GRANT(전체 동기화)는 전용 실행기에서</b> 한다. 실행기 작업도 잠금 안에서 세션을 한 번 더 끊고, 끊기에 실패하면 넓히는 GRANT 를
   *       커밋하지 않는다(기존 계약 그대로). 그 사이 시작된 정상 세션이 함께 끊길 수 있는데, 등급 정의 변경은 드문 관리 작업이라 수용한다(기존 주석과 같은
   *       판단). 테넌트당 큐에서 기다리는 작업은 하나로 합친다({@link #levelsSyncPending}).
   * </ol>
   *
   * <p><b>남는 창.</b> 커밋 직전에 잠금 안에서 <b>옛</b> 등급을 읽고 슬롯을 고른 실행 준비(JIT)가, 동기 끊기가 끝난 <b>뒤</b>에 스크립트를
   * 접속시킬 수 있다. 이 세션은 옛 위치 슬롯을 쥔 채 살아 남아, 실행기의 전체 동기화가 두 번째로 끊을 때까지 같은 슬롯의 뒤이은 JIT 가 새 위치 기준으로 넓힌
   * GRANT 를 볼 수 있다. 예전에도 같은 모양의 창이 있었지만(동기 리스너가 잠금을 잡기까지) 이제 그 길이는 실행기 큐 대기만큼이다. 커밋 뒤에 시작하는 실행은 JIT
   * 가 같은 잠금 안에서 커밋된 새 등급을 읽어 자기 슬롯을 맞추므로 이 창과 무관하다.
   *
   * <p>세션 끊기가 실패해도 커밋 결과는 바꾸지 않고 로그만 남긴 채 실행기 작업은 넘긴다 — 실행기 작업이 다시 끊고, 실패하면 넓히지 않는다(fail-closed).
   * 실행기 큐가 차서 작업이 버려지면 표식을 지워 다음 변경이 다시 넣게 하고, 그때까지는 새 실행의 JIT 가 자기 슬롯을, 일 1회 동기화가 나머지를 회복한다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLevelsChanged(SecurityLevelsChangedEvent event) {
    long tenantId = event.tenantId();
    try {
      TenantContext.runScoped(tenantId, sync::terminateSlotSessionsForLevelsChange);
    } catch (RuntimeException e) {
      log.warn(
          "등급 정의 변경 후 PYTHON 슬롯 세션 끊기 실패(실행기 동기화가 다시 끊고, 실패하면 넓히지 않는다): tenant={} kind={}",
          tenantId,
          event.kind(),
          e);
    }
    if (!levelsSyncPending.add(tenantId)) {
      return; // 아직 시작하지 않은 전체 동기화가 큐에 있다 — 그 작업이 시작할 때 이 변경까지 읽는다.
    }
    boolean accepted =
        submitScoped(
            tenantId,
            () -> {
              // 시작할 때 표식을 지운다 — 실행 중에 커밋된 변경이 작업을 하나 더 넣게(필드 주석 참고).
              levelsSyncPending.remove(tenantId);
              sync.syncTenantForLevelsChange();
            },
            "등급 정의 변경(kind=" + event.kind() + ")");
    if (!accepted) {
      levelsSyncPending.remove(tenantId); // 버려졌다 — 다음 변경이 다시 넣을 수 있게
    }
  }

  /**
   * 데이터셋 하나의 등급 변경 — 그 데이터셋 테이블만 다시 맞춘다. 세션은 끊지 않는다: 슬롯↔등급 위치 대응은 그대로라, 등급을 내려 늘어나는 GRANT 는 이미 그
   * 등급을 볼 자격이 있는 슬롯에만 가고(실행 주체 자격 안), 올려 줄어드는 GRANT 는 동기화가 커밋된 뒤의 다음 쿼리부터 막힌다.
   *
   * <p><b>왜 실행기인가(WD-624).</b> 커밋 콜백에서 동기로 돌리면 요청 하나가 플랫폼 풀 커넥션 2개를 쥔다({@link #onLevelsChanged}
   * 참고). 비동기로 미루면서 남는 창: 등급을 <b>올린</b> 직후 큐가 빌 때까지 <b>이미 실행 중이던</b> 세션은 그 테이블을 계속 읽을 수 있다(예전에도 커밋과
   * 동기화 사이의 짧은 창은 있었고, 이제 큐 대기만큼 길어진다). 작업이 <b>버려지면</b>(큐 포화·종료 중) 이미 실행 중이던 세션은 그 슬롯을 건드리는 다음 동기화
   * — 일 1회 동기화나 그 슬롯의 JIT — 까지 읽기 권한을 유지한다. <b>새 실행</b>은 안전하다 — 실행 준비(JIT)가 같은 잠금 안에서 커밋된 데이터셋 등급을
   * 읽어 자기 슬롯의 남는 권한을 회수한 뒤에야 스크립트를 띄운다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDatasetLevelChanged(DatasetSecurityLevelChangedEvent event) {
    long datasetId = event.datasetId();
    submitScoped(
        event.tenantId(),
        () -> sync.syncDataset(datasetId),
        "데이터셋 등급 변경(dataset=" + datasetId + ")");
  }

  /**
   * 동기화 작업을 전용 실행기에 넘긴다 — 실행기 스레드에서 이벤트의 테넌트를 다시 세우고(실행기 스레드에는 TenantContext 가 없다), 실패하면 테넌트·사유를 담아
   * WARN 만 남긴다(다음 동기화가 회복). 예외는 여기서 한 번만 받아 로그로 끝내므로 실행기의 안전망 catch 까지 가지 않는다. 큐 포화·종료로 버려지면 실행기가
   * 버림 WARN 을 남기고 false 를 돌려준다(커밋 콜백을 막거나 실패시키지 않는다).
   *
   * @return 받아들였으면 true, 버려졌으면 false
   */
  private boolean submitScoped(long tenantId, Runnable work, String logContext) {
    return executor.submit(
        () -> {
          try {
            TenantContext.runScoped(tenantId, work);
          } catch (RuntimeException e) {
            log.warn("{} 후 PYTHON 읽기 권한 동기화 실패(다음 동기화에서 회복): tenant={}", logContext, tenantId, e);
          }
        });
  }
}

package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.EmbeddingHostingChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 스펙 §4.3 외부 벡터 정리 트리거(등급 변경·등급 정의 변경·임베딩 호스팅 변경)를 받아 정리를 돌린다.
 *
 * <p>커밋 후(AFTER_COMMIT)·전용 풀(indexExecutor)에서 — 등급 변경 트랜잭션을 늦추지 않고, 커밋되지 않은 변경으로 벡터를 지우지 않는다. 트랜잭션
 * 밖 발행(임베딩 설정 저장)도 받도록 fallbackExecution.
 *
 * <p><b>테넌트는 이벤트의 tenantId 로 세운다.</b> indexExecutor 의 데코레이터는 발행 스레드의 문맥을 복사하지만, 발행 스레드가 문맥 없는 배경
 * 경로이거나 다른 테넌트로 서 있을 수 있다 — 이벤트가 말하는 테넌트가 정리 대상이다. runScoped 는 끝나면 진입 전 값으로 복원한다.
 */
@Component
@RequiredArgsConstructor
public class AiVectorPurgeListener {

  private final AiVectorPurgeService purgeService;

  /** 데이터셋 1건 등급 변경(수동·자동 상향·TEMP 지정·복제 상속). 현재 상태 기준 정리라 원인·방향과 무관하게 같은 메서드를 부른다. */
  @Async("indexExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onDatasetLevelChanged(DatasetSecurityLevelChangedEvent e) {
    TenantContext.runScoped(e.tenantId(), purgeService::purgeDisallowed);
  }

  /** 등급 정의 변경(ai_policy 강화·삭제 이동·순서 변경) — 데이터셋별 판정이 통째로 달라질 수 있어 테넌트 전체를 다시 본다. */
  @Async("indexExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onLevelsChanged(SecurityLevelsChangedEvent e) {
    TenantContext.runScoped(e.tenantId(), purgeService::purgeDisallowed);
  }

  /** 임베딩 호스팅 선언 변경 — SELF_HOSTED→EXTERNAL 일 때만 실제로 지울 것이 생긴다(반대 방향은 무동작). */
  @Async("indexExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onEmbeddingHostingChanged(EmbeddingHostingChangedEvent e) {
    TenantContext.runScoped(e.tenantId(), purgeService::purgeDisallowed);
  }
}

package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
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

  private final PythonReadGrantSync sync;

  /**
   * 등급 정의 변경(추가·수정·삭제·순서) — 등급 위치가 바뀌므로 테넌트 전체를 다시 맞추고, 새 GRANT 커밋 전에 실행 중인 슬롯 롤 세션을 끊는다(실행 중 스크립트가
   * 넓어진 슬롯으로 자격 밖 등급을 읽지 못하게 — {@link PythonReadGrantSync#syncTenantForLevelsChange()}).
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLevelsChanged(SecurityLevelsChangedEvent event) {
    try {
      TenantContext.runScoped(event.tenantId(), sync::syncTenantForLevelsChange);
    } catch (RuntimeException e) {
      log.warn(
          "등급 정의 변경 후 PYTHON 읽기 권한 동기화 실패(다음 동기화에서 회복): tenant={} kind={}",
          event.tenantId(),
          event.kind(),
          e);
    }
  }

  /**
   * 데이터셋 하나의 등급 변경 — 그 데이터셋 테이블만 다시 맞춘다. 세션은 끊지 않는다: 슬롯↔등급 위치 대응은 그대로라, 등급을 내려 늘어나는 GRANT 는 이미 그
   * 등급을 볼 자격이 있는 슬롯에만 가고(실행 주체 자격 안), 올려 줄어드는 GRANT 는 다음 쿼리부터 막힌다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDatasetLevelChanged(DatasetSecurityLevelChangedEvent event) {
    try {
      TenantContext.runScoped(event.tenantId(), () -> sync.syncDataset(event.datasetId()));
    } catch (RuntimeException e) {
      log.warn(
          "데이터셋 등급 변경 후 PYTHON 읽기 권한 동기화 실패(다음 동기화에서 회복): tenant={} dataset={}",
          event.tenantId(),
          event.datasetId(),
          e);
    }
  }
}

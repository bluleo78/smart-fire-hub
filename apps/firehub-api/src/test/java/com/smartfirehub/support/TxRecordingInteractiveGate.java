package com.smartfirehub.support;

import com.smartfirehub.analytics.service.AnalyticsQueryConcurrencyGate;
import com.smartfirehub.analytics.service.InteractiveQueryConcurrencyGate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 허가를 얻는 시점의 트랜잭션 활성 여부를 기록하는 화면 쿼리 게이트 테스트 대역(WD-599).
 *
 * <p>상한은 끈다 — 기다리지 않고 기록만 한다. 기다리는 동안 커넥션을 쥐지 않으려면 허가는 반드시 트랜잭션(=커넥션)을 열기 전에 얻어야 하고, Tibero 테넌트는 아예
 * 얻지 않아야 한다. 차트·대시보드·SQL 편집기 트랜잭션 구간 테스트가 함께 쓴다.
 */
public final class TxRecordingInteractiveGate extends InteractiveQueryConcurrencyGate {

  /** acquire 호출마다 그 시점의 트랜잭션 활성 여부(호출이 없으면 빈 목록). */
  public final List<Boolean> acquireTx = new ArrayList<>();

  public TxRecordingInteractiveGate() {
    super(0, 0, 0);
  }

  @Override
  public AnalyticsQueryConcurrencyGate.Permit acquire(long userId) {
    acquireTx.add(TransactionSynchronizationManager.isActualTransactionActive());
    return super.acquire(userId);
  }
}

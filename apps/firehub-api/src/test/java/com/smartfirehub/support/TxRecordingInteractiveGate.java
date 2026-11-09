package com.smartfirehub.support;

import com.smartfirehub.analytics.service.AnalyticsQueryConcurrencyGate;
import com.smartfirehub.analytics.service.InteractiveQueryConcurrencyGate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 허가를 얻는 시점의 트랜잭션 활성 여부를 기록하는 화면 쿼리 게이트 테스트 대역(WD-599).
 *
 * <p>허가는 트랜잭션(=커넥션)이 없는 동안 얻어야 하고(차트·대시보드는 조회 트랜잭션과 실행 트랜잭션 사이, SQL 편집기는 진입부), Tibero 테넌트는 아예 얻지
 * 않아야 한다 — 기록 목록의 값과 길이(=acquire 호출 수)로 확인한다. 인자 없는 생성자는 상한을 꺼 기록만 하고, 상한 생성자는 실제 대기·429 까지 그대로
 * 둔다(게이트가 찬 상태의 테스트용).
 */
public final class TxRecordingInteractiveGate extends InteractiveQueryConcurrencyGate {

  /** acquire 호출마다 그 시점의 트랜잭션 활성 여부(호출이 없으면 빈 목록). */
  public final List<Boolean> acquireTx = new ArrayList<>();

  public TxRecordingInteractiveGate() {
    this(0, 0, 0);
  }

  /** 상한을 켠 기록 게이트 — 인자는 {@link InteractiveQueryConcurrencyGate} 생성자와 같다. */
  public TxRecordingInteractiveGate(int maxTotal, int maxPerUser, long waitMs) {
    super(maxTotal, maxPerUser, waitMs);
  }

  @Override
  public AnalyticsQueryConcurrencyGate.Permit acquire(long userId) {
    acquireTx.add(TransactionSynchronizationManager.isActualTransactionActive());
    return super.acquire(userId);
  }
}

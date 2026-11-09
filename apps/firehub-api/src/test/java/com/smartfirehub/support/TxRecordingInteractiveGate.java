package com.smartfirehub.support;

import com.smartfirehub.analytics.service.AnalyticsQueryConcurrencyGate;
import com.smartfirehub.analytics.service.InteractiveQueryConcurrencyGate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 허가를 얻는 시점의 트랜잭션 활성 여부를 기록하는 화면 쿼리 게이트 테스트 대역(WD-599).
 *
 * <p>허가는 트랜잭션(=커넥션)이 없는 동안 얻어야 하고(차트·대시보드는 조회 트랜잭션과 실행 트랜잭션 사이, SQL 편집기는 존재 확인·판정과 쓰기 트랜잭션 사이),
 * Tibero 테넌트는 아예 얻지 않아야 한다 — 기록 목록의 값과 길이(=acquire 호출 수)로 확인한다. 인자 없는 생성자는 상한을 꺼 기록만 하고, 상한 생성자는 실제
 * 대기·429 까지 그대로 둔다(게이트가 찬 상태의 테스트용).
 */
public final class TxRecordingInteractiveGate extends InteractiveQueryConcurrencyGate {

  /**
   * acquire 호출마다 그 시점의 트랜잭션 활성 여부(호출이 없으면 빈 목록). 요청을 다른 스레드에서 돌리는 테스트(WD-638)가 함께 읽으므로 동기화 목록이다.
   */
  public final List<Boolean> acquireTx = Collections.synchronizedList(new ArrayList<>());

  /**
   * 허가를 얻는 순간(기록 직후, 실제 대기 전) 부르는 훅. 허가를 기다리는 동안 다른 요청이 커밋한 변경(예: 관리자의 열람자 등급 하향, WD-682)을 스레드 없이
   * 결정적으로 흉내 낸다. 기본은 아무것도 하지 않는다.
   */
  private final Runnable onAcquire;

  public TxRecordingInteractiveGate() {
    this(0, 0, 0);
  }

  /** 상한을 켠 기록 게이트 — 인자는 {@link InteractiveQueryConcurrencyGate} 생성자와 같다. */
  public TxRecordingInteractiveGate(int maxTotal, int maxPerUser, long waitMs) {
    this(maxTotal, maxPerUser, waitMs, () -> {});
  }

  /** 상한을 끈 기록 게이트 + 허가 순간 훅. */
  public TxRecordingInteractiveGate(Runnable onAcquire) {
    this(0, 0, 0, onAcquire);
  }

  /** 상한을 켠 기록 게이트 + 허가 순간 훅 — 허가 대기가 429 로 끝나는 동안의 변경을 흉내 낸다(WD-689). */
  public TxRecordingInteractiveGate(int maxTotal, int maxPerUser, long waitMs, Runnable onAcquire) {
    super(maxTotal, maxPerUser, waitMs);
    this.onAcquire = onAcquire;
  }

  @Override
  public AnalyticsQueryConcurrencyGate.Permit acquire(long userId) {
    acquireTx.add(TransactionSynchronizationManager.isActualTransactionActive());
    onAcquire.run();
    return super.acquire(userId);
  }
}

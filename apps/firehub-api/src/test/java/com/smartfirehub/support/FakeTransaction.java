package com.smartfirehub.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * 단위 테스트용 가짜 트랜잭션(WD-675) — 실제 DB·트랜잭션 관리자 없이 「트랜잭션 안」을 흉내 낸다. 잠금 층은 트랜잭션 표시를, 삭제 잠금 증표는 완료 콜백
 * 등록(동기화 활성)을 확인하므로 둘 다 켠다. 증표 무효화를 시험하려면 {@link #complete} 로 완료 콜백을 부른다.
 *
 * <p>스레드 로컬 상태라 테스트마다 {@link #end} 로 반드시 지운다(@AfterEach).
 */
public final class FakeTransaction {

  private FakeTransaction() {}

  /** 실제 트랜잭션 표시와 동기화 콜백을 켠다. */
  public static void begin() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
  }

  /** 트랜잭션이 커밋으로 끝난 것처럼 등록된 완료 콜백(afterCompletion)을 부른다. */
  public static void complete() {
    TransactionSynchronizationUtils.invokeAfterCompletion(
        TransactionSynchronizationManager.getSynchronizations(),
        TransactionSynchronization.STATUS_COMMITTED);
  }

  /** 켠 상태를 모두 지운다 — begin 하지 않은 테스트에서 불러도 된다. */
  public static void end() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }
}

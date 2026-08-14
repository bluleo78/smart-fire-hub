package com.smartfirehub.global.transaction;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 현재 트랜잭션이 커밋된 뒤에 작업을 실행한다. 트랜잭션이 없으면 즉시 실행한다.
 *
 * <p>왜 필요한가: JobRunr 의 StorageProvider 는 DataSource 에서 자기 커넥션을 직접 얻으므로
 * 현재 스프링 트랜잭션에 <b>합류하지 않는다</b>. 그래서 트랜잭션 안에서 {@code enqueue} 하면 잡
 * 레코드가 즉시 커밋되어, 백그라운드 서버가 <b>바깥 트랜잭션이 커밋되기 전에</b> 잡을 집어갈 수
 * 있다. 그 잡이 방금 만든 행을 id 로 다시 읽으면 아직 보이지 않아 실패한다(문서가 PENDING 에
 * 영구 정지하는 형태).
 *
 * <p>P2-a 에서 요청 경로에 트랜잭션 경계를 부여하면서 생긴 문제다 — 그 전에는 각 리포지토리
 * 호출이 즉시 커밋돼 enqueue 시점에 행이 이미 보였다.
 */
public final class AfterCommitRunner {

  private AfterCommitRunner() {}

  /**
   * 트랜잭션 커밋 후 실행을 예약한다.
   *
   * <p>트랜잭션 동기화가 활성이 아니면(트랜잭션 밖 호출, 일부 테스트) 즉시 실행해 호출자가
   * 트랜잭션 유무를 신경 쓰지 않아도 되게 한다.
   */
  public static void run(Runnable work) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      work.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            work.run();
          }
        });
  }
}

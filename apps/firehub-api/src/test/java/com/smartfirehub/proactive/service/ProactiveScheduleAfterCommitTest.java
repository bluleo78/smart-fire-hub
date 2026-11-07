package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-584 — 스마트 작업 삭제의 예약 해제는 커밋 뒤에만 일어나야 한다.
 *
 * <p>삭제 트랜잭션이 감사 실패 등으로 롤백되면 행은 「켜짐」으로 남는데, 예전처럼 트랜잭션 안에서 예약을 풀면 서버 재시작 전까지 작업이 돌지 않는다. 클래스 레벨
 * {@code @Transactional} 을 쓰지 않는다 — 실제 커밋·롤백으로 확인해야 한다. 작업 행은 만들지 않는다(메모리 예약만 본다).
 */
class ProactiveScheduleAfterCommitTest extends IntegrationTestBase {

  /** 다른 테스트의 작업 id 와 겹치지 않게 큰 값을 쓴다. */
  private static final long JOB_ID = 958_400_001L;

  @Autowired private ProactiveJobSchedulerService schedulerService;

  @AfterEach
  void tearDown() {
    schedulerService.unregisterSchedule(JOB_ID);
  }

  @Test
  void 커밋되면_예약이_해제된다() {
    schedulerService.registerSchedule(JOB_ID, "0 0 3 * * *", "Asia/Seoul");

    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          schedulerService.unregisterScheduleAfterCommit(JOB_ID);
          // 커밋 전에는 아직 걸려 있다
          assertThat(schedulerService.isScheduled(JOB_ID)).isTrue();
        });

    assertThat(schedulerService.isScheduled(JOB_ID)).isFalse();
  }

  @Test
  void 롤백되면_예약이_그대로_남는다() {
    schedulerService.registerSchedule(JOB_ID, "0 0 3 * * *", "Asia/Seoul");

    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          schedulerService.unregisterScheduleAfterCommit(JOB_ID);
          status.setRollbackOnly();
        });

    assertThat(schedulerService.isScheduled(JOB_ID)).isTrue();
  }

  @Test
  void 트랜잭션_밖에서_부르면_바로_해제된다() {
    schedulerService.registerSchedule(JOB_ID, "0 0 3 * * *", "Asia/Seoul");

    schedulerService.unregisterScheduleAfterCommit(JOB_ID);

    assertThat(schedulerService.isScheduled(JOB_ID)).isFalse();
  }
}

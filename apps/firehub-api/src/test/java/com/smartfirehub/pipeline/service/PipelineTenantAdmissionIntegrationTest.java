package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 워크스페이스별 동시 실행 관문(WD-439) — 기다렸다 완료되는 경로.
 *
 * <ul>
 *   <li>상한(여기서는 1)을 넘는 실행은 실패하지 않고 PENDING 으로 기다리다가 앞 실행이 끝나면 시작해 완료된다
 *   <li>기다리던 실행을 취소하면 곧바로 CANCELLED 이고 대기열에서 빠지며, 자리를 막지 않아 다음 실행이 돈다
 * </ul>
 *
 * <p>대기 한도는 넉넉히(60초) 둔다(WD-478) — 부하 중 앞 실행이 늦게 끝나도 한도로 닫히지 않게. 한도 초과는 {@link
 * PipelineTenantAdmissionWaitLimitIntegrationTest} 가 짧은 한도로 본다.
 */
@TestPropertySource(properties = {"app.pipeline.tenant-concurrency.max-wait=PT60S"})
class PipelineTenantAdmissionIntegrationTest extends AbstractPipelineTenantAdmissionIntegrationTest {

  /** 상한을 넘는 실행은 실패하지 않고 PENDING 으로 기다렸다가, 앞 실행이 끝나면 시작해 완료된다. */
  @Test
  void executionBeyondLimit_waitsPendingThenCompletes() {
    closeGate();
    Long slow = readingPipeline("slow", gateTable);
    Long quick = readingPipeline("quick", freeTable);

    Long first = executionService.executePipeline(slow, userId);
    awaitStatus(slow, first, "RUNNING");
    Long second = executionService.executePipeline(quick, userId);

    // 앞 실행이 도는 동안 둘째는 러너에 넘어가지 않고 PENDING 그대로 대기열에 있다.
    sleep(500);
    assertThat(pipelineService.getExecutionById(quick, second).status()).isEqualTo("PENDING");
    assertThat(tenantAdmission.isWaiting(second)).isTrue();
    // 대기 중에도 등록부에 남아 하트비트를 받는다 — 정리기가 「중단됨」으로 닫지 않는다.
    assertThat(executionRegistry.isRegistered(second)).isTrue();

    openGate();
    assertThat(awaitTerminal(slow, first).status()).isEqualTo("COMPLETED");
    assertThat(awaitTerminal(quick, second).status()).isEqualTo("COMPLETED");
    awaitReleased(second);
  }

  /** 기다리던 실행을 취소하면 곧바로 CANCELLED 이고 대기열·등록부에서 빠져, 그 뒤 실행이 자리를 받는다. */
  @Test
  void cancellingWaitingExecution_removesItFromQueue() {
    closeGate();
    Long slow = readingPipeline("slow", gateTable);
    Long quick = readingPipeline("quick", freeTable);

    Long first = executionService.executePipeline(slow, userId);
    awaitStatus(slow, first, "RUNNING");
    Long waiting = executionService.executePipeline(quick, userId);
    Long next = executionService.executePipeline(quick, userId);

    assertThat(executionService.cancelExecution(quick, waiting, userId).status())
        .isEqualTo("CANCELLED");
    assertThat(tenantAdmission.isWaiting(waiting)).isFalse();
    assertThat(executionRegistry.isRegistered(waiting)).isFalse();

    openGate();
    assertThat(awaitTerminal(slow, first).status()).isEqualTo("COMPLETED");
    assertThat(awaitTerminal(quick, next).status()).isEqualTo("COMPLETED");
    assertThat(pipelineService.getExecutionById(quick, waiting).status()).isEqualTo("CANCELLED");
  }
}

package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 워크스페이스별 동시 실행 관문(WD-439) — 대기 한도를 넘긴 실행은 한국어 안내로 FAILED 가 된다(내부 풀 이름·영어 원문 없음).
 *
 * <p>한도 초과를 빨리 보려고 대기 한도를 짧게(4초) 둔다. 「기다렸다 완료」 테스트와 한 클래스에 두면 그 테스트가 부하 중 이 한도로 가끔 닫혀서(WD-478)
 * 클래스를 나눴다.
 */
@TestPropertySource(properties = {"app.pipeline.tenant-concurrency.max-wait=PT4S"})
class PipelineTenantAdmissionWaitLimitIntegrationTest extends AbstractPipelineTenantAdmissionIntegrationTest {

  /** 대기 한도(여기서는 4초)를 넘긴 실행은 한국어 안내로 FAILED 가 되고, 스텝은 실행하지 않은 채 닫힌다. */
  @Test
  void executionWaitingPastLimit_failsWithKoreanMessage() {
    closeGate();
    Long slow = readingPipeline("slow", gateTable);
    Long quick = readingPipeline("quick", freeTable);

    Long first = executionService.executePipeline(slow, userId);
    awaitStatus(slow, first, "RUNNING");
    Long starved = executionService.executePipeline(quick, userId);
    try {
      ExecutionDetailResponse detail = awaitTerminal(quick, starved);

      assertThat(detail.status()).isEqualTo("FAILED");
      // 조회 응답은 실행 주체가 아닌 열람자에게 문구를 가리므로(WD-27) 저장된 문구를 직접 본다(RLS 라 테넌트 트랜잭션 안에서).
      String stored =
          inTenantFixture(
              () ->
                  dsl.fetchOne("SELECT error_message FROM pipeline_execution WHERE id = ?", starved)
                      .get(0, String.class));
      assertThat(stored)
          .isEqualTo(PipelineTenantAdmission.WAIT_TIMEOUT_MESSAGE)
          .doesNotContain("pipeline-tenant")
          .doesNotContain("Connection is not available");
      assertThat(detail.stepExecutions())
          .allSatisfy(step -> assertThat(step.status()).isEqualTo("SKIPPED"));
      awaitReleased(starved);
    } finally {
      openGate();
      awaitTerminal(slow, first);
    }
  }
}

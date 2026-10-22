package com.smartfirehub.pipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.service.TriggerService;
import org.junit.jupiter.api.Test;

/**
 * 외부 트리거 응답의 건너뜀 사유 가리기(WD-169).
 *
 * <p>토큰·웹훅 주소만 가진 바깥 호출자에게 「트리거를 만든 사람이 정지·제거됨」, 「실행 권한 없음」 같은 워크스페이스 안의 인사·권한 상태가 그대로 나가지 않아야 한다.
 * 두 사유는 같은 일반 문구로 바뀌고, 그 밖의 사유는 그대로다.
 */
class ExternalTriggerReasonTest {

  @Test
  void 만든_사람의_자격_사유는_바깥에_같은_일반_문구로_나간다() {
    assertThat(ExternalTriggerController.externalReason(TriggerService.REASON_EXECUTOR_INACTIVE))
        .isEqualTo(ExternalTriggerController.REASON_EXECUTOR_NOT_ELIGIBLE_EXTERNAL);
    assertThat(
            ExternalTriggerController.externalReason(
                TriggerService.REASON_EXECUTOR_NO_EXECUTE_PERMISSION))
        .isEqualTo(ExternalTriggerController.REASON_EXECUTOR_NOT_ELIGIBLE_EXTERNAL);
    assertThat(ExternalTriggerController.REASON_EXECUTOR_NOT_ELIGIBLE_EXTERNAL)
        .doesNotContain("정지")
        .doesNotContain("제거")
        .doesNotContain("권한");
  }

  @Test
  void 그_밖의_사유는_그대로_나간다() {
    assertThat(ExternalTriggerController.externalReason(TriggerService.REASON_PIPELINE_INACTIVE))
        .isEqualTo(TriggerService.REASON_PIPELINE_INACTIVE);
  }
}

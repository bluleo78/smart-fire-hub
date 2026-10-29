package com.smartfirehub.pipeline.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.smartfirehub.securitylevel.access.SqlAccessResult;
import java.util.Set;

/**
 * 러너 테스트 공용 — 사용자 SQL 판정({@link PipelineSecurityGate#checkStepSqlForRun})의 기본 스텁(WD-426). 러너는 이 판정의
 * 읽기·쓰기 데이터셋 id 로 「REPLACE 가 자기 출력을 읽거나 고침」을 판정하므로, 목 기본값(null)이면 역참조에서 끊긴다. 여러 러너 테스트 클래스가 같은 기본값을
 * 쓰도록 한 곳에 둔다.
 */
final class RunnerSqlAccessStubs {

  private RunnerSqlAccessStubs() {}

  /**
   * 기본은 아무 데이터셋도 참조하지 않는 허용 결과(자기 출력 판정이 늘 「그대로」)를 돌려준다. 판정 결과를 쓰는 테스트는 자기 스텁으로 덮어쓴다 — 쓰지 않는 테스트가
   * 있어 lenient.
   */
  static void allowSqlWithoutDatasetsByDefault(PipelineSecurityGate pipelineSecurityGate) {
    lenient()
        .when(pipelineSecurityGate.checkStepSqlForRun(any(), any()))
        .thenReturn(new SqlAccessResult(true, null, null, null, Set.of(), Set.of(), true));
  }
}

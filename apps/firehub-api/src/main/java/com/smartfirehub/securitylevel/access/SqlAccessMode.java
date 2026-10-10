package com.smartfirehub.securitylevel.access;

/**
 * SQL 판정 모드.
 *
 * <ul>
 *   <li>INTERACTIVE — 애드혹·데이터셋 /query·저장 쿼리·차트: VIEW + 쓰기 하향 거부(스펙 §4.1)
 *   <li>PIPELINE_SAVE — 스텝 저장 시: VIEW 만, {@code step_ref_N} 더미 제외(판단 사항 4)
 *   <li>PIPELINE_RUN — 스텝 실행 시: VIEW. 쓰기 대상 하향은 거부하지 않고 PipelineSecurityGate 가 자동 상향한다(S4, 스펙 §4.5)
 * </ul>
 */
public enum SqlAccessMode {
  INTERACTIVE,
  PIPELINE_SAVE,
  PIPELINE_RUN
}

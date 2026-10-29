package com.smartfirehub.securitylevel.access;

/**
 * SQL 판정 모드.
 *
 * <ul>
 *   <li>INTERACTIVE — 애드혹·데이터셋 /query·저장 쿼리·차트: VIEW + 쓰기 하향 거부(스펙 §4.1)
 *   <li>INTERACTIVE_SAVE — 저장 쿼리 저장 시점(WD-409): INTERACTIVE 와 같은 파서·Tibero 한정 이름 요구·쓰기 하향 거부·감사
 *       동작(SQL)으로 판정하되, 데이터를 읽지 않으므로 AI 정책 판정과 리포트 등급 기록(noteRead)은 하지 않는다
 *   <li>PIPELINE_SAVE — 스텝 저장 시: VIEW 만, {@code step_ref_N} 더미 제외(판단 사항 4)
 *   <li>PIPELINE_RUN — 스텝 실행 시: VIEW. 쓰기 대상 하향은 거부하지 않고 PipelineSecurityGate 가 자동 상향한다(S4, 스펙 §4.5)
 * </ul>
 */
public enum SqlAccessMode {
  INTERACTIVE,
  INTERACTIVE_SAVE,
  PIPELINE_SAVE,
  PIPELINE_RUN;

  /**
   * 대화형 규칙(파서 선택·Tibero 한정 이름 요구·쓰기 하향 거부·SQL 감사)을 따르는 모드인가. AI 정책·리포트 등급처럼 "데이터를 읽는" 동작은 이 값이 아니라
   * INTERACTIVE 만 따로 비교한다.
   */
  public boolean interactiveRules() {
    return this == INTERACTIVE || this == INTERACTIVE_SAVE;
  }
}

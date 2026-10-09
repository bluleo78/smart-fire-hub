package com.smartfirehub.securitylevel.access;

/**
 * 접근 거부 감사의 동작 구분(스펙 §4.6, 보충 스펙 §3 대상 목록). 감사 metadata.action 에 이름 그대로 남는다.
 *
 * <ul>
 *   <li>VIEW — 데이터셋 ID 경로 열람(404 로 가려지는 것 포함)
 *   <li>SQL — 대화형 SQL(애드혹·데이터셋 /query·저장 쿼리·메트릭)
 *   <li>PIPELINE — 파이프라인 저장·실행 판정
 *   <li>DATASET_REFS — SQL 이 아닌 데이터셋 id 목록 판정(AI_CLASSIFY 입력·저장 쿼리 연결·트리거 감시)
 *   <li>EXPORT — 내보내기 정책 거부
 *   <li>AI — 흐름 A 의 POLICY_BLOCKED(AI·공유 정책 차단 — 사유 AI_EXTERNAL_DENIED·AI_DENIED·SHARE_DENIED)
 * </ul>
 */
public enum AccessDenialAction {
  VIEW,
  SQL,
  PIPELINE,
  DATASET_REFS,
  EXPORT,
  AI
}

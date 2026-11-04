package com.smartfirehub.analytics.repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 애드혹 분석 쿼리 실행 기록(V136 analytics_query_run) — 쿼리 결과 내보내기의 서버 재실행 근거(스펙 §4.4).
 *
 * <p>구현은 {@code JooqAnalyticsQueryRunRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface AnalyticsQueryRunRepository {

  /** 실행 기록 — 재판정·재실행에 필요한 SQL 원문과 행 상한. */
  record Run(UUID id, String sqlText, int maxRows) {}

  /**
   * 기록을 남기고 id 를 돌려준다. 만료 행 정리는 삽입 경로가 아니라 주기 정리({@link #deleteExpired})가 맡는다 — 삽입 때 사용자별로 지우면 다시
   * 실행하지 않는 사용자의 SQL 원문이 무기한 남았다(code-review 7).
   */
  UUID insert(long userId, String sqlText, int maxRows);

  /**
   * 현재 테넌트(GUC)의 보존 기간 지난 기록을 모두 지우고 지운 행 수를 돌려준다. 주기 정리가 테넌트마다 부른다 — RLS 가 현재 테넌트 행만 보이게 하므로 테넌트
   * 컨텍스트 없이 부르면 0행이다. created_at 단독 범위라 idx_analytics_query_run_created 를 탄다.
   */
  int deleteExpired();

  /**
   * 소유자 본인의 보존 기간 안 기록. 다른 사용자·만료·없음은 모두 empty — 호출자가 같은 404 로 응답해 남의 실행 기록 존재를 드러내지 않는다(Review
   * Focus 3).
   */
  Optional<Run> findOwned(UUID id, long userId);
}

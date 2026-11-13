package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 애드혹 분석 쿼리 실행 기록(analytics_query_run) MyBatis 매퍼(WD-710). SQL 은 {@code
 * mybatis/analytics/AnalyticsQueryRunMapper.xml}. 테넌트는 정책(RLS)이 맡는다.
 *
 * <p>id 는 문자열로 주고받는다 — PG 는 uuid 열이라 문장에서 CAST 하고, Tibero 는 VARCHAR2(36) 열이다.
 */
@PlatformMapper
public interface AnalyticsQueryRunMapper {

  /** 자바가 만든 id 로 한 행을 넣는다. 넣은 행 수. */
  int insert(
      @Param("id") String id,
      @Param("userId") long userId,
      @Param("sqlText") String sqlText,
      @Param("maxRows") int maxRows);

  /** 보존 기간(DB 시계 1시간) 지난 행을 지우고 지운 행 수를 돌려준다. */
  int deleteExpired();

  /** 주인의 보존 기간 안 기록(SQL·행 상한) — 없으면 null. */
  RunRow selectOwned(@Param("id") String id, @Param("userId") long userId);

  /** 실행 기록 한 행의 SQL 원문·행 상한. id 는 호출자가 이미 알고 있어 읽지 않는다. */
  record RunRow(String sqlText, int maxRows) {}
}

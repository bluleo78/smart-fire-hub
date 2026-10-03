package com.smartfirehub.global.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.ResultQuery;
import org.jooq.exception.DataAccessException;

/**
 * 범위 밖 날짜·시각을 <b>다른 날짜로 바꾸지 않고</b> PG 텍스트 원문으로 돌려주는 jOOQ plain SQL 조회 헬퍼(#769).
 *
 * <p><b>왜 필요한가.</b> 데이터셋 데이터 탭 조회·단건 조회·CSV/Excel 내보내기는 {@code dsl.fetch(sql, params)} 로 읽은 {@code
 * java.sql.Date}/{@code Timestamp} 를 그대로 응답·파일에 썼다. 그래서 PostgreSQL 은 허용하지만 Java 날짜 객체가 표현하지 못하는 값이
 * 오류 없이 다른 값이 됐다 — {@code 10000-01-01} → {@code 0000-01-01}, BC 소실, {@code infinity} → {@code
 * 8994-08-17}, {@code -infinity} 타임스탬프 → 부호 없는 {@code 292269055-12-03}, 1582 전환 공백·DST 공백 시각 이동.
 * 애드혹 SQL 경로(#768)와 근본 원인이 같다.
 *
 * <p><b>방법.</b> 바인드 처리·실행 리스너(테넌트 GUC 등)를 그대로 쓰도록 jOOQ 로 실행해 JDBC ResultSet 을 받고, #768 의 {@link
 * AdhocTemporalValues} 로 감싸 jOOQ 에 다시 읽힌다. 판정 로직(범위 밖·Java 값의 원문 재현 여부)은 그 래퍼 한 곳에만 있다. 정상 값은 jOOQ
 * 가 예전과 같은 타입으로 읽으므로 응답 JSON·내보내기 문자열 형태가 같고, 이상 값 셀만 PG 텍스트 문자열이 된다(내보내기 Excel 은 모든 셀을 문자열로 쓰므로
 * 문자열 셀이 된다).
 *
 * <p>PostGIS 타입 이름 고정({@code AdhocSpatialTypeNames}, #759)은 여기서 하지 않는다 — 데이터셋 조회는 geometry 를 {@code
 * ST_AsGeoJSON} 텍스트로 읽으므로 해당이 없고, 예전 동작을 바꾸지 않기 위해서다.
 */
public final class TemporalSafeFetch {

  private TemporalSafeFetch() {}

  /**
   * plain SQL 을 바인드 값과 함께 실행해 결과를 돌려준다. 바인드 값이 없으면 바인드 없이 실행한다(기존 {@code dsl.fetch(sql)} 과 같은 호출
   * 형태).
   */
  public static Result<Record> fetch(DSLContext dsl, String sql, Object... bindings) {
    ResultQuery<Record> query =
        bindings != null && bindings.length > 0
            ? dsl.resultQuery(sql, bindings)
            : dsl.resultQuery(sql);
    // fetchResultSet 은 커서를 열어 둔 채 돌려주므로 반드시 닫는다 — 트랜잭션 밖(비동기 내보내기)에서 커넥션 누수 방지
    try (ResultSet rs = query.fetchResultSet()) {
      AdhocTemporalValues temporal = new AdhocTemporalValues(rs);
      return temporal.apply(dsl, dsl.fetch(temporal.proxy()));
    } catch (SQLException e) {
      throw new DataAccessException("결과 읽기 실패: " + e.getMessage(), e);
    }
  }

  /** {@link #fetch} 의 단건 버전 — 행이 없으면 null. */
  public static Record fetchOne(DSLContext dsl, String sql, Object... bindings) {
    Result<Record> result = fetch(dsl, sql, bindings);
    return result.isEmpty() ? null : result.get(0);
  }
}

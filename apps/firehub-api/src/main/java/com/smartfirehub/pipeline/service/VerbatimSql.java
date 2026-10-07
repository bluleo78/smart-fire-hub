package com.smartfirehub.pipeline.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;

/**
 * 판정·검증한 사용자 SQL 문자열을 <b>바이트 그대로</b> 보내는 조리법 — 정적 {@link Statement} + JDBC 이스케이프 처리 끔. jOOQ 의
 * {@code execute(String)}·{@code fetch(String)} 은 문자열을 템플릿으로 해석해({@code {0}}·{@code ?} 등) 판정한 문자열과
 * 다른 SQL 을 보낼 수 있다(판정 = 실행이 깨진다). 오류 메시지는 jOOQ 와 같은 {@code SQL [...]; <PG 메시지>} 형태로 유지한다(스텝 오류 표시
 * 계약).
 *
 * <p>파이프라인 SQL 싱크(SqlScriptExecutor·SqlColumnProbe)만 쓴다 — 사용자 SQL 실행 지점이므로 접근 범위를 패키지로 좁히고,
 * SqlGateArchitectureTest 가 이 두 클래스 밖의 접근을 거부한다.
 */
final class VerbatimSql {

  private VerbatimSql() {}

  /** 결과 없이 실행한다(dsl 의 현재 트랜잭션 커넥션). */
  static void execute(DSLContext dsl, String sql) {
    dsl.connection(
        conn -> {
          try (Statement st = conn.createStatement()) {
            st.setEscapeProcessing(false);
            st.execute(sql);
          } catch (SQLException e) {
            throw new DataAccessException("SQL [" + sql + "]; " + e.getMessage(), e);
          }
        });
  }

  /** 조회 결과를 jOOQ {@link Result} 로 읽는다(dsl 의 현재 트랜잭션 커넥션). */
  static Result<Record> fetch(DSLContext dsl, String sql) {
    return dsl.connectionResult(
        conn -> {
          try (Statement st = conn.createStatement()) {
            st.setEscapeProcessing(false);
            try (ResultSet rs = st.executeQuery(sql)) {
              return dsl.fetch(rs);
            }
          } catch (SQLException e) {
            throw new DataAccessException("SQL [" + sql + "]; " + e.getMessage(), e);
          }
        });
  }
}

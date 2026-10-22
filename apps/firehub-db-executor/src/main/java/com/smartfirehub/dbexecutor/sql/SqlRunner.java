package com.smartfirehub.dbexecutor.sql;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code /execute/sql} 실행기(스펙 §4.3).
 *
 * <ol>
 *   <li>검증(fail-closed, api 를 믿지 않음): 본문 {@link TiberoSqlGuard#requireAllowedStatement} + {@link
 *       TiberoSqlGuard#requireNoForeignSchemaCalls}, 선행 문장 {@link PreStatementRule}. 거부되면 커넥션을 빌리지
 *       않는다.
 *   <li>한 트랜잭션(autoCommit=false 풀): 선행 문장마다 {@code LOCK TABLE … IN EXCLUSIVE MODE}(별도 문장) → DELETE,
 *       이어서 본문 → COMMIT. 예외·시간 초과면 ROLLBACK.
 *   <li>시간 제한: 문장마다 {@code setQueryTimeout}(기본 50초), 요청 전체(컨트롤러 도착부터, 기본 55초)가 넘으면 {@link Deadline}
 *       이 실행 중 문장을 cancel. 기한이 이미 지났으면 커넥션을 빌리지 않고, 빌린 뒤에 지났으면 실행하지 않으며, 본문이 끝난 뒤 커밋 직전에 지났으면
 *       ROLLBACK 한다.
 *   <li>커넥션 대여(로그인) 실패: 그 테넌트의 기술자 캐시를 지우고 실패로 끝낸다 — 비밀번호가 바뀌었으면 다음 요청이 새 기술자로 풀을 다시 만든다(풀 고갈 대기 시간
 *       초과는 지우지 않는다). 대여·기한·실패 처리 뼈대는 {@link LeasedExecution} 이 ProbeRunner 와 함께 쓴다.
 *   <li>rows·columns 는 선행 문장이 없고 첫 키워드가 SELECT·WITH 일 때만 채운다.
 * </ol>
 */
@Component
public class SqlRunner {

  static final String EMPTY_QUERY = "실행할 SQL 이 비어 있습니다.";

  private final ExecutionTimeouts.Sql limits;
  private final LeasedExecution execution;

  public SqlRunner(
      TenantPools pools, ExecutionTimeouts timeouts, StoreDescriptorClient descriptors) {
    this.limits = timeouts.sql();
    this.execution = new LeasedExecution(pools, descriptors);
  }

  /** 전체 시간 초과 문구(기본 55초면 「실행 시간이 55초를 넘어 취소했습니다.」). */
  public static String timeoutMessage(int seconds) {
    return "실행 시간이 " + seconds + "초를 넘어 취소했습니다.";
  }

  /**
   * 본문을 재검증하고 실행할 형태(끝 {@code ;} 제거)로 돌려준다. ProbeRunner 도 같은 검사를 쓴다.
   *
   * @throws TiberoSqlGuard.Rejected 비었거나 가드 위반
   */
  static String validatedBody(String query, String tenantSchema) {
    if (query == null || query.isBlank()) {
      throw new TiberoSqlGuard.Rejected(EMPTY_QUERY);
    }
    TiberoSqlGuard.requireAllowedStatement(query);
    TiberoSqlGuard.requireNoForeignSchemaCalls(query, tenantSchema);
    // 가드를 통과했으면 따옴표·주석이 닫혀 있으므로 어휘 분석기가 예외를 던지지 않는다.
    return TiberoSqlLexer.stripTrailingSemicolon(query);
  }

  /**
   * 검증 → 실행 → 커밋. 오류는 예외가 아니라 {@code success=false} 응답으로 돌려준다.
   *
   * @param d 테넌트 저장소 기술자(스키마·계정)
   * @param query 본문 한 문장
   * @param preStatements 선행 DELETE 목록(없으면 빈 목록 또는 null)
   * @param deadline 요청 전체 기한(컨트롤러 도착 시점부터). 닫기는 호출자 몫이다.
   */
  public SqlExecuteResponse run(
      StoreDescriptor d, String query, List<String> preStatements, Deadline deadline) {
    List<String> pres = preStatements == null ? List.of() : preStatements;
    String body;
    List<String> locks = new ArrayList<>();
    try {
      body = validatedBody(query, d.schema());
      for (String pre : pres) {
        locks.add(PreStatementRule.lockFor(pre, d.schema()));
      }
    } catch (TiberoSqlGuard.Rejected e) {
      return SqlExecuteResponse.failure(e.getMessage(), null, "실행 전 검증에서 거부되어 실행하지 않았습니다.");
    }
    String first = TiberoSqlLexer.firstKeyword(body);
    boolean returnRows = pres.isEmpty() && (first.equals("SELECT") || first.equals("WITH"));

    List<String> log = new ArrayList<>();
    return execution.withLease(
        d,
        deadline,
        new LeasedExecution.Failures<>() {
          @Override
          public SqlExecuteResponse expiredBeforeBorrow() {
            return SqlExecuteResponse.failure(timeoutMessage(), null, "시간 초과로 실행하지 않았습니다.");
          }

          @Override
          public SqlExecuteResponse connectFailed() {
            return SqlExecuteResponse.failure(
                LeasedExecution.CONNECT_FAILED, null, "저장소 연결 실패로 실행하지 않았습니다.");
          }

          @Override
          public SqlExecuteResponse timedOut() {
            log.add("시간 초과로 취소하고 롤백함");
            return SqlExecuteResponse.failure(timeoutMessage(), null, String.join("\n", log));
          }

          @Override
          public SqlExecuteResponse dbError(String message, DbError error) {
            log.add("오류로 롤백함");
            return SqlExecuteResponse.failure(message, error, String.join("\n", log));
          }

          @Override
          public SqlExecuteResponse unexpected() {
            log.add("오류로 롤백함");
            return SqlExecuteResponse.failure(
                LeasedExecution.UNEXPECTED, null, String.join("\n", log));
          }
        },
        c -> {
          for (int i = 0; i < pres.size(); i++) {
            update(c, deadline, locks.get(i));
            int deleted = update(c, deadline, pres.get(i));
            log.add("선행 문장 " + (i + 1) + ": 출력 테이블을 잠그고 " + deleted + "행 삭제");
          }
          BodyResult out = body(c, deadline, body, returnRows);
          log.add("본문 실행: " + out.rowCount + "행 " + (out.query ? "조회" : "반영"));
          if (deadline.expired()) {
            // 본문이 끝났어도 기한을 넘겼으면 커밋하지 않는다 — api 는 이미 포기했을 수 있다.
            throw new Deadline.TimedOut();
          }
          c.commit();
          log.add("커밋 완료");
          return new SqlExecuteResponse(
              true, out.rows, out.columns, out.rowCount, String.join("\n", log), null, null);
        });
  }

  /** 전체 시간 초과 문구(설정한 전체 제한 초). */
  private String timeoutMessage() {
    return timeoutMessage(limits.totalTimeoutSeconds());
  }

  /** 결과 집합 없는 문장(LOCK·DELETE) 하나를 실행하고 반영 행 수를 돌려준다. */
  private int update(Connection c, Deadline deadline, String sql)
      throws SQLException, Deadline.TimedOut {
    return deadline.execute(
        c,
        limits.statementTimeoutSeconds(),
        st -> {
          st.execute(sql);
          return Math.max(st.getUpdateCount(), 0);
        });
  }

  /** 본문 실행 결과. */
  private record BodyResult(
      boolean query, int rowCount, List<Map<String, Object>> rows, List<String> columns) {}

  /** 본문을 실행한다. 결과 집합이 있고 returnRows 면 행을 읽어 변환한다. */
  private BodyResult body(Connection c, Deadline deadline, String sql, boolean returnRows)
      throws SQLException, Deadline.TimedOut {
    return deadline.execute(
        c,
        limits.statementTimeoutSeconds(),
        st -> {
          boolean hasResultSet = st.execute(sql);
          if (!hasResultSet) {
            return new BodyResult(false, Math.max(st.getUpdateCount(), 0), null, null);
          }
          if (!returnRows) {
            // 선행 문장이 있는 SELECT: 행을 돌려주지 않는다(스펙 §4.2).
            return new BodyResult(true, 0, null, null);
          }
          try (ResultSet rs = st.getResultSet()) {
            return readRows(rs);
          }
        });
  }

  private static BodyResult readRows(ResultSet rs) throws SQLException {
    if (rs == null) {
      return new BodyResult(true, 0, List.of(), List.of());
    }
    ResultSetMetaData md = rs.getMetaData();
    int n = md.getColumnCount();
    List<String> columns = new ArrayList<>(n);
    // 컬럼 메타(타입·정밀도·자릿수)는 컬럼마다 한 번만 읽는다 — 칸마다 읽으면 행 수 × 컬럼 수만큼 드라이버를 부른다.
    ValueConverter.ColumnMeta[] metas = new ValueConverter.ColumnMeta[n];
    for (int i = 1; i <= n; i++) {
      columns.add(ValueConverter.columnName(md.getColumnLabel(i)));
      metas[i - 1] = ValueConverter.ColumnMeta.of(md, i);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    while (rs.next()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 1; i <= n; i++) {
        row.put(columns.get(i - 1), ValueConverter.read(rs, i, metas[i - 1]));
      }
      rows.add(row);
    }
    return new BodyResult(true, rows.size(), rows, columns);
  }
}

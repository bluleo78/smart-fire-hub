package com.smartfirehub.dbexecutor.sql;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.LoadResponse;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * {@code /execute/load} 실행기(스펙 §3.3) — 행 목록을 스테이징 테이블에 한 트랜잭션으로 넣는다.
 *
 * <ol>
 *   <li>검증(fail-closed): 대상은 {@link LoadTableRule#requireStaging}, 컬럼 이름은 앱 이름 형식({@code
 *       [a-z][a-z0-9_]{0,29}})만, 값은 앱 타입으로 변환({@link LoadValues#coerce}). 하나라도 실패하면 커넥션을 빌리지 않고 실패로
 *       끝낸다. 행이 없으면 아무것도 하지 않고 성공(Python 실행기 {@code insert_batch} 와 같다).
 *   <li>OWNER 풀(autoCommit=false)에서 한 트랜잭션: 먼저 {@code SELECT NVL(MAX("_SEQ"),0)} 으로 기준값을 한 번 읽고,
 *       {@code INSERT INTO "S"."STG_…" ("_SEQ", "COL", …) VALUES (?, ?, …)} 를 500행씩 {@code
 *       addBatch}/{@code executeBatch}, 끝에 COMMIT({@code _SEQ} = 기준값 + 행 번호 — 같은 스테이징에 여러 번 적재해도 입력
 *       순서가 이어진다). 어느 배치든 실패하거나 기한이 지나면 ROLLBACK — 일부 행만 남지 않는다. 스테이징의 {@code ID}·{@code
 *       _UPDATED_AT} 은 넣지 않는다(출력 테이블로 옮길 때 채운다).
 *   <li>시간 제한: 요청 전체 기한은 컨트롤러가 도착 즉시 잡는다({@code app.load.timeout-seconds} 이하). 문장 제한도 적재 제한이고 배치마다
 *       남은 시간으로 다시 건다. 대여·기한·실패 처리 뼈대는 {@link LeasedExecution} 이 {@link SqlRunner} 와 함께 쓴다.
 * </ol>
 */
@Component
public class LoadRunner {

  /** 한 번의 {@code executeBatch} 로 보내는 최대 행 수(api {@code DataStoreBatching.CHUNK} 와 같다). */
  static final int BATCH_SIZE = 500;

  static final String BAD_ROW = "요청 형식이 올바르지 않습니다(빈 행이거나 행이 null 입니다).";

  static final String BAD_COLUMN = "컬럼 이름이 올바르지 않습니다.";

  /** 앱 컬럼 이름 규칙(api DataTableNames) + Tibero 식별자 30자 제한. 대문자·따옴표·{@code _} 시작은 받지 않는다. */
  private static final Pattern COLUMN = Pattern.compile("[a-z][a-z0-9_]{0,29}");

  private final int loadSeconds;
  private final LeasedExecution execution;

  public LoadRunner(
      @Qualifier("tenantPools") TenantPools pools,
      ExecutionTimeouts timeouts,
      StoreDescriptorClient descriptors) {
    this.loadSeconds = timeouts.load().timeoutSeconds();
    this.execution = new LeasedExecution(pools, descriptors);
  }

  /** 적재 시간 초과 문구(요청의 전체 제한 초). */
  static String timeoutMessage(int seconds) {
    return "적재 시간이 " + seconds + "초를 넘어 취소했습니다.";
  }

  /**
   * 앱 컬럼 이름(소문자)을 Tibero 물리 이름(대문자)으로 바꾼다 — api {@code TiberoNames.physical} 과 같은 규칙({@code
   * Locale.ROOT}, 터키어 로캘의 i→İ 방지). 공유 벡터로 고정한다.
   */
  static String physicalColumn(String appName) {
    return appName.toUpperCase(Locale.ROOT);
  }

  /**
   * 검증 → 한 트랜잭션 배치 INSERT → 커밋. 오류는 예외가 아니라 {@code success=false} 로 돌려준다.
   *
   * @param d 테넌트 저장소 기술자(스키마·계정)
   * @param table 스테이징 테이블 이름({@code STG_<24hex>})
   * @param rows 행 목록
   * @param columnTypes 컬럼 이름 → 앱 타입(없으면 null 또는 빈 맵)
   * @param deadline 요청 전체 기한(컨트롤러 도착 시점부터). 닫기는 호출자 몫이다.
   */
  public LoadResponse load(
      StoreDescriptor d,
      String table,
      List<Map<String, Object>> rows,
      Map<String, String> columnTypes,
      Deadline deadline) {
    String qualified;
    try {
      qualified = LoadTableRule.requireStaging(table, d.schema());
    } catch (TiberoSqlGuard.Rejected e) {
      return LoadResponse.failure(e.getMessage(), null);
    }
    List<Map<String, Object>> input = rows == null ? List.of() : rows;
    if (input.isEmpty()) {
      return new LoadResponse(true, 0, null, null);
    }

    // 행 하나가 null 이거나 컬럼이 없으면(빈 객체) 넣을 값이 없다 — 연결을 빌리기 전에 거부한다.
    for (Map<String, Object> row : input) {
      if (row == null || row.isEmpty()) {
        return LoadResponse.failure(BAD_ROW, null);
      }
    }
    // 컬럼 = 모든 행 키의 합집합(처음 나온 순서). 없는 키는 NULL 로 넣는다.
    Set<String> columnSet = new LinkedHashSet<>();
    for (Map<String, Object> row : input) {
      columnSet.addAll(row.keySet());
    }
    List<String> columns = new ArrayList<>(columnSet);
    for (String c : columns) {
      if (c == null || !COLUMN.matcher(c).matches()) {
        return LoadResponse.failure(BAD_COLUMN, null);
      }
    }
    Map<String, String> types = columnTypes == null ? Map.of() : columnTypes;
    List<Object[]> values;
    try {
      values = convert(input, columns, types);
    } catch (ConversionFailed e) {
      return LoadResponse.failure(e.getMessage(), null);
    }

    String sql = insertSql(qualified, columns);
    String baseSql = seqBaseSql(qualified);
    int total = deadline.totalSeconds();
    return execution.withLease(
        d,
        deadline,
        new LeasedExecution.Failures<>() {
          @Override
          public LoadResponse expiredBeforeBorrow() {
            return LoadResponse.failure(timeoutMessage(total), null);
          }

          @Override
          public LoadResponse connectFailed() {
            return LoadResponse.failure(LeasedExecution.CONNECT_FAILED, null);
          }

          @Override
          public LoadResponse timedOut() {
            return LoadResponse.failure(timeoutMessage(total), null);
          }

          @Override
          public LoadResponse dbError(String message, DbError error) {
            return LoadResponse.failure(message, error);
          }

          @Override
          public LoadResponse unexpected() {
            return LoadResponse.failure(LeasedExecution.UNEXPECTED, null);
          }
        },
        c -> {
          // _SEQ 기준값은 같은 트랜잭션에서 한 번만 읽는다. 한 실행이 같은 스테이징에 /execute/load 를 여러 번 부르고(순차),
          // 호출 사이에 _SEQ 가 이어져야 옮길 때 ORDER BY "_SEQ" 가 입력 순서를 보장한다.
          long base = readSeqBase(c, deadline, baseSql);
          int loaded = insertAll(c, deadline, sql, values, base);
          if (deadline.expired()) {
            // 마지막 배치가 끝났어도 기한을 넘겼으면 커밋하지 않는다 — api 는 이미 포기했을 수 있다.
            throw new Deadline.TimedOut();
          }
          c.commit();
          return new LoadResponse(true, loaded, null, null);
        });
  }

  /** 스테이징의 현재 최대 {@code _SEQ}(비었으면 0) 조회 문장. */
  static String seqBaseSql(String qualifiedTable) {
    return "SELECT NVL(MAX(\"_SEQ\"), 0) FROM " + qualifiedTable;
  }

  /** 이 요청이 매길 {@code _SEQ} 의 시작 기준값(첫 행은 기준값 + 1). */
  private static long readSeqBase(java.sql.Connection c, Deadline deadline, String sql)
      throws SQLException, Deadline.TimedOut {
    return deadline.execute(
        c,
        deadline.totalSeconds(),
        st -> {
          try (java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
          }
        });
  }

  /**
   * {@code INSERT INTO "S"."STG_…" ("_SEQ", "A", "B") VALUES (?, ?, ?)} — 첫 바인드는 입력 순번({@code
   * _SEQ}). 컬럼 이름은 검증을 마친 값만 대문자로 인용한다.
   */
  private static String insertSql(String qualifiedTable, List<String> columns) {
    StringBuilder cols = new StringBuilder("\"_SEQ\"");
    StringBuilder marks = new StringBuilder("?");
    for (int i = 0; i < columns.size(); i++) {
      cols.append(", ");
      marks.append(", ");
      cols.append('"').append(physicalColumn(columns.get(i))).append('"');
      marks.append('?');
    }
    return "INSERT INTO " + qualifiedTable + " (" + cols + ") VALUES (" + marks + ")";
  }

  /** 행마다 컬럼 순서대로 앱 타입 변환을 마친 값 배열을 만든다. */
  private static List<Object[]> convert(
      List<Map<String, Object>> rows, List<String> columns, Map<String, String> types)
      throws ConversionFailed {
    List<Object[]> out = new ArrayList<>(rows.size());
    for (int r = 0; r < rows.size(); r++) {
      Map<String, Object> row = rows.get(r);
      Object[] vals = new Object[columns.size()];
      for (int i = 0; i < columns.size(); i++) {
        String col = columns.get(i);
        String type = types.get(col);
        try {
          vals[i] = LoadValues.coerce(row.get(col), type);
        } catch (LoadValues.InvalidValue e) {
          // 값은 싣지 않는다 — 컬럼·타입·행 번호만.
          throw new ConversionFailed(
              "컬럼 " + col + " 의 값을 " + type + " 형식으로 바꿀 수 없습니다(" + (r + 1) + "번째 행).");
        }
      }
      out.add(vals);
    }
    return out;
  }

  /** 값 변환 실패(검증 단계). */
  private static final class ConversionFailed extends Exception {
    ConversionFailed(String message) {
      super(message, null, false, false);
    }
  }

  /** 한 문장을 준비해 500행씩 배치 실행한다. 배치 전에 기한을 확인하고 남은 시간으로 문장 제한을 다시 건다. */
  private int insertAll(
      java.sql.Connection c, Deadline deadline, String sql, List<Object[]> values, long seqBase)
      throws SQLException, Deadline.TimedOut {
    return deadline.executePrepared(
        c,
        sql,
        loadSeconds,
        ps -> {
          int loaded = 0;
          for (int start = 0; start < values.size(); start += BATCH_SIZE) {
            if (deadline.expired()) {
              throw new Deadline.TimedOut();
            }
            ps.setQueryTimeout(deadline.statementTimeout(loadSeconds));
            int end = Math.min(start + BATCH_SIZE, values.size());
            for (int r = start; r < end; r++) {
              Object[] row = values.get(r);
              ps.setObject(1, seqBase + r + 1);
              for (int i = 0; i < row.length; i++) {
                LoadValues.bind(ps, i + 2, row[i]);
              }
              ps.addBatch();
            }
            ps.executeBatch();
            loaded += end - start;
          }
          return loaded;
        });
  }
}

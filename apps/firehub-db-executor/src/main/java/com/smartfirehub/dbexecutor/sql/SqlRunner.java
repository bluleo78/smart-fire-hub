package com.smartfirehub.dbexecutor.sql;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.PipelineStopClient;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 *   <li>시간 제한: 문장마다 {@code setQueryTimeout}(기본 50초), 본문 전체(기본 55초)가 넘으면 {@link Deadline} 이 실행 중 문장을
 *       cancel. 기한이 이미 지났으면 커넥션을 빌리지 않고, 빌린 뒤에 지났으면 실행하지 않으며, 본문이 끝난 뒤 커밋 직전에 지났으면 ROLLBACK 한다.
 *   <li>선행 문장과 본문의 기한 분리(스펙 §6, WD-236): 사용자 시간 제한(기본 50/55초 또는 {@code timeout_seconds})은
 *       <b>본문에만</b> 건다 — REPLACE 의 비우기 DELETE 는 기존 출력 행 수에 비례해 오래 걸려, 한 기한을 나눠 쓰면 삭제가 본문 시간을 깎아 같은
 *       본문이 출력이 클수록 실패한다(2웨이브 30만 행 52초 실패). 그래서
 *       <ul>
 *         <li>선행 문장이 없으면 기존 그대로: 컨트롤러가 도착 시점에 잡은 본문 전체 기한 하나로 기술자 조회·대여·본문을 덮는다.
 *         <li>있으면 컨트롤러가 도착 시점 기한을 {@code 선행 상한 + 본문 전체 기한} 으로 잡는다({@link
 *             ExecutionTimeouts.Sql#requestSeconds}). 선행 문장은 그 기한에서 본문 몫을 뺀 시간(도착부터 선행 상한) 안에서 돌고 —
 *             넘기면 {@link #preTimeoutMessage} 로 롤백·본문 미실행 — 본문 직전에 새 본문 기한(본문 전체 초)을 잡아 본문과 커밋 직전
 *             검사를 그 기한으로 한다. 본문은 언제나 도착 기한 안에서 끝나므로 api 의 HTTP 대기({@code 본문 대기 + 선행 상한})를 넘겨 커밋하지
 *             않는다.
 *       </ul>
 *   <li>커넥션 대여(로그인) 실패: 그 테넌트의 기술자 캐시를 지우고 실패로 끝낸다 — 비밀번호가 바뀌었으면 다음 요청이 새 기술자로 풀을 다시 만든다(풀 고갈 대기 시간
 *       초과는 지우지 않는다). 대여·기한·실패 처리 뼈대는 {@link LeasedExecution} 이 ProbeRunner 와 함께 쓴다.
 *   <li>rows·columns 는 선행 문장이 없고 첫 키워드가 SELECT·WITH 일 때만 채운다.
 *   <li>잠금 대기 중 취소(WD-482): 요청에 파이프라인 실행 id 가 있으면 각 출력 잠금을 얻은 <b>직후</b>·비우기와 본문 전에 api 에 중지 요청을
 *       되묻는다({@link PipelineStopClient}). 중지면 ROLLBACK 하고 {@link SqlExecuteResponse#cancelled} 로
 *       답한다(출력 그대로).
 * </ol>
 */
@Component
public class SqlRunner {

  // 이름이 logger 인 이유: run 안의 실행 요약 목록(log)과 겹치지 않게.
  private static final Logger logger = LoggerFactory.getLogger(SqlRunner.class);

  static final String EMPTY_QUERY = "실행할 SQL 이 비어 있습니다.";

  private final ExecutionTimeouts.Sql limits;

  /** 선행 문장의 문장마다·합계 상한(초, {@code app.sql.pre-statement-timeout-seconds}). */
  private final int preSeconds;

  private final LeasedExecution execution;

  /** 잠금 뒤 중지 요청 확인(WD-482). null 이면 되묻지 않는다(중지 확인이 필요 없는 테스트·라이브 조립). */
  private final PipelineStopClient stopClient;

  /** 중지 확인 없이 조립한다(테스트·라이브 테스트용 — 실행 id 가 와도 되묻지 않는다). */
  public SqlRunner(
      @Qualifier("tenantPools") TenantPools pools,
      ExecutionTimeouts timeouts,
      StoreDescriptorClient descriptors) {
    this(pools, timeouts, descriptors, null);
  }

  @Autowired
  public SqlRunner(
      @Qualifier("tenantPools") TenantPools pools,
      ExecutionTimeouts timeouts,
      StoreDescriptorClient descriptors,
      PipelineStopClient stopClient) {
    this.limits = timeouts.sql();
    this.preSeconds = limits.preStatementTimeoutSeconds();
    this.execution = new LeasedExecution(pools, descriptors);
    this.stopClient = stopClient;
  }

  /** 전체 시간 초과 문구(기본 55초면 「실행 시간이 55초를 넘어 취소했습니다.」). */
  public static String timeoutMessage(int seconds) {
    return "실행 시간이 " + seconds + "초를 넘어 취소했습니다.";
  }

  /** 선행 문장 상한 초과 문구(기본 1800초면 「기존 출력 정리(선행 문장)가 1800초 안에 끝나지 않아 취소했습니다.」, WD-236). */
  public static String preTimeoutMessage(int seconds) {
    return "기존 출력 정리(선행 문장)가 " + seconds + "초 안에 끝나지 않아 취소했습니다.";
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
   * @param deadline 요청 기한(컨트롤러 도착 시점부터, 선행 문장이 있으면 {@code 선행 상한 + 본문 전체 기한} — {@link
   *     ExecutionTimeouts.Sql#requestSeconds}). 닫기는 호출자 몫이다.
   */
  public SqlExecuteResponse run(
      StoreDescriptor d, String query, List<String> preStatements, Deadline deadline) {
    return run(d, query, preStatements, deadline, null);
  }

  /**
   * {@link #run(StoreDescriptor, String, List, Deadline)} 에 요청별 시간 제한을 더한 판.
   *
   * @param requestedSeconds 요청의 {@code timeout_seconds}(컨트롤러가 {@code app.load.timeout-seconds} 로 자른
   *     값). 있으면 본문의 문장 제한·전체 기한과 시간 초과 문구가 이 값이다. null 이면 기존 50/55초 그대로. 선행 문장에는 걸지 않는다.
   */
  public SqlExecuteResponse run(
      StoreDescriptor d,
      String query,
      List<String> preStatements,
      Deadline deadline,
      Integer requestedSeconds) {
    return run(d, query, preStatements, deadline, requestedSeconds, (BooleanSupplier) null);
  }

  /**
   * {@link #run(StoreDescriptor, String, List, Deadline, Integer)} 에 파이프라인 실행 id 를 더한 판(WD-482) —
   * 컨트롤러가 요청의 {@code executionId} 를 그대로 넘긴다.
   *
   * @param executionId 파이프라인 실행 id. 양수이고 중지 확인 클라이언트가 있으면 출력 잠금 직후 api 에 중지 요청을 되묻는다. null·0 이하면
   *     되묻지 않는다(기존 동작)
   */
  public SqlExecuteResponse run(
      StoreDescriptor d,
      String query,
      List<String> preStatements,
      Deadline deadline,
      Integer requestedSeconds,
      Long executionId) {
    BooleanSupplier stopRequested =
        stopClient != null && executionId != null && executionId > 0
            ? stopClient.check(d.tenantId(), executionId)
            : null;
    return run(d, query, preStatements, deadline, requestedSeconds, stopRequested);
  }

  /**
   * 실행 본체.
   *
   * @param stopRequested 파이프라인 중지 요청 확인(WD-482, 없으면 null). 각 출력 잠금({@code LOCK TABLE})을 얻은
   *     <b>직후</b> 부른다 — 잠금 대기는 api 러너의 「SQL 실행 직전」 체크포인트보다 뒤라 그 사이 들어온 취소를 api 가 볼 수 없었고, 잠금을 얻은 뒤
   *     사용자 SQL 이 끝까지 돌아 출력을 다시 교체했다. 중지면 비우기·본문을 보내지 않고 ROLLBACK 해 {@link
   *     SqlExecuteResponse#cancelled} 로 답한다.
   */
  SqlExecuteResponse run(
      StoreDescriptor d,
      String query,
      List<String> preStatements,
      Deadline deadline,
      Integer requestedSeconds,
      BooleanSupplier stopRequested) {
    int statementSeconds =
        requestedSeconds != null ? requestedSeconds : limits.statementTimeoutSeconds();
    int totalSeconds = requestedSeconds != null ? requestedSeconds : limits.totalTimeoutSeconds();
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
    // 어느 기한이 터졌는지 — 선행 상한 초과면 true(시간 초과 문구를 고른다). 한 요청 스레드에서만 쓴다.
    boolean[] preTimedOut = {false};
    return execution.withLease(
        d,
        deadline,
        new LeasedExecution.Failures<>() {
          @Override
          public SqlExecuteResponse expiredBeforeBorrow() {
            return SqlExecuteResponse.failure(
                timeoutMessage(totalSeconds), null, "시간 초과로 실행하지 않았습니다.");
          }

          @Override
          public SqlExecuteResponse connectFailed() {
            return SqlExecuteResponse.failure(
                LeasedExecution.CONNECT_FAILED, null, "저장소 연결 실패로 실행하지 않았습니다.");
          }

          @Override
          public SqlExecuteResponse timedOut() {
            log.add("시간 초과로 취소하고 롤백함");
            String message =
                preTimedOut[0] ? preTimeoutMessage(preSeconds) : timeoutMessage(totalSeconds);
            return SqlExecuteResponse.failure(message, null, String.join("\n", log));
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
          if (pres.isEmpty()) {
            // 선행 문장 없음: 기존 그대로 도착 기한 하나로 본문·커밋 직전 검사를 한다(기술자 조회·대여까지 같은 기한).
            return bodyAndCommit(c, deadline, statementSeconds, body, returnRows, log);
          }
          try {
            for (int i = 0; i < pres.size(); i++) {
              update(c, deadline, preStatementSeconds(deadline, totalSeconds), locks.get(i));
              // 잠금 대기 중에 들어온 취소(WD-482) — 비우기·본문 전에 확인한다. 중지면 되돌리고 끝낸다(출력 그대로, 잠금도 풀린다).
              if (stopRequested != null && stopRequestedQuietly(stopRequested, d.tenantId())) {
                LeasedExecution.rollbackQuietly(c);
                log.add("선행 문장 " + (i + 1) + ": 출력 테이블을 잠근 뒤 파이프라인 취소 요청을 확인해 롤백함");
                return SqlExecuteResponse.cancelled(String.join("\n", log));
              }
              int deleted =
                  update(c, deadline, preStatementSeconds(deadline, totalSeconds), pres.get(i));
              log.add("선행 문장 " + (i + 1) + ": 출력 테이블을 잠그고 " + deleted + "행 삭제");
            }
            if (preBudgetExceeded(deadline, totalSeconds)) {
              // 선행이 (취소에 반응하지 않고) 상한을 넘겨 끝났다 — 본문을 시작하면 도착 기한 뒤에 커밋할 수 있다.
              throw new Deadline.TimedOut();
            }
          } catch (Deadline.TimedOut e) {
            preTimedOut[0] = true;
            throw e;
          } catch (SQLException e) {
            if (preBudgetExceeded(deadline, totalSeconds)) {
              // 선행 상한 시점의 문장 제한(setQueryTimeout) 또는 도착 기한 cancel 로 생긴 오류 — 드라이버 문구 대신 고정 문구로 돌려준다.
              preTimedOut[0] = true;
              throw new Deadline.TimedOut();
            }
            throw e;
          }
          // 본문 기한은 본문 시작 시점부터 잰다 — 선행이 오래 걸려도 본문은 자기 기한을 온전히 받는다(스펙 §6).
          try (Deadline bodyDeadline = new Deadline(totalSeconds)) {
            try {
              return bodyAndCommit(c, bodyDeadline, statementSeconds, body, returnRows, log);
            } catch (SQLException e) {
              if (bodyDeadline.expired()) {
                // 본문 기한 cancel 로 생긴 오류다. LeasedExecution 은 도착 기한만 보므로 여기서 시간 초과로 바꾼다.
                throw new Deadline.TimedOut();
              }
              throw e;
            }
          }
        });
  }

  /**
   * 중지 확인 — 확인마저 실패하면(api 장애 등) 중지 요청이 없는 것으로 보고 원래대로 실행한다(api 러너의 isStopRequestedQuietly·Python
   * 실행기와 같은 판단: 확인 실패로 정상 실행을 실패시키지 않는다).
   */
  private static boolean stopRequestedQuietly(BooleanSupplier stopRequested, long tenantId) {
    try {
      return stopRequested.getAsBoolean();
    } catch (RuntimeException e) {
      // 원인 메시지에는 비밀이 없다(PipelineStopClient 가 본문·토큰을 싣지 않는다).
      logger.warn("파이프라인 중지 요청 확인 실패 — 그대로 실행합니다 tenant={} 원인={}", tenantId, e.getMessage());
      return false;
    }
  }

  /**
   * 본문을 실행하고, 그 기한이 아직 남았으면 커밋한다.
   *
   * @param bodyDeadline 본문 기한 — 선행 문장이 없으면 도착 기한, 있으면 본문 시작 시점에 잡은 본문 기한. 커밋 직전 검사도 이 기한이다.
   */
  private SqlExecuteResponse bodyAndCommit(
      Connection c,
      Deadline bodyDeadline,
      int statementSeconds,
      String body,
      boolean returnRows,
      List<String> log)
      throws SQLException, Deadline.TimedOut {
    BodyResult out = body(c, bodyDeadline, statementSeconds, body, returnRows);
    log.add("본문 실행: " + out.rowCount + "행 " + (out.query ? "조회" : "반영"));
    if (bodyDeadline.expired()) {
      // 본문이 끝났어도 기한을 넘겼으면 커밋하지 않는다 — api 는 이미 포기했을 수 있다.
      throw new Deadline.TimedOut();
    }
    c.commit();
    log.add("커밋 완료");
    return new SqlExecuteResponse(
        true, out.rows, out.columns, out.rowCount, String.join("\n", log), null, null);
  }

  /**
   * 선행 몫을 다 썼는지: 도착 기한의 남은 시간이 본문 몫({@code bodySeconds}) 아래면 선행 상한(도착부터)을 넘긴 것이다. 이 시점에 본문을 시작하면
   * 본문이 도착 기한 뒤에 끝나 api 가 포기한 뒤 커밋할 수 있다.
   */
  private static boolean preBudgetExceeded(Deadline deadline, int bodySeconds) {
    return deadline.remaining().compareTo(Duration.ofSeconds(bodySeconds)) < 0;
  }

  /**
   * 선행 문장 하나의 {@code setQueryTimeout}(초): 선행 상한과 남은 선행 몫(도착 기한 남은 시간 - 본문 몫, 올림, 최소 1초) 중 작은 값. 도착
   * 기한의 cancel 은 본문 몫까지 더 늦게 오므로, 멈춘 선행 문장이 선행 상한 시점에 스스로 끝나게 하는 장치다.
   */
  private int preStatementSeconds(Deadline deadline, int bodySeconds) {
    long nanos = deadline.remaining().minusSeconds(bodySeconds).toNanos();
    long seconds = Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    return (int) Math.min(preSeconds, seconds);
  }

  /** 결과 집합 없는 문장(LOCK·DELETE) 하나를 실행하고 반영 행 수를 돌려준다. */
  private int update(Connection c, Deadline deadline, int statementSeconds, String sql)
      throws SQLException, Deadline.TimedOut {
    return deadline.execute(
        c,
        statementSeconds,
        st -> {
          st.execute(sql);
          return Math.max(st.getUpdateCount(), 0);
        });
  }

  /** 본문 실행 결과. */
  record BodyResult(
      boolean query, int rowCount, List<Map<String, Object>> rows, List<String> columns) {}

  /** 본문을 실행한다. 결과 집합이 있고 returnRows 면 행을 읽어 변환한다. */
  private BodyResult body(
      Connection c, Deadline deadline, int statementSeconds, String sql, boolean returnRows)
      throws SQLException, Deadline.TimedOut {
    return deadline.execute(
        c,
        statementSeconds,
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

  /** 결과 집합을 행·컬럼으로 읽는다(/execute/sql 경로 — 컬럼 이름은 변환한 그대로, 기존 파이프라인 출력과 같다). */
  static BodyResult readRows(ResultSet rs) throws SQLException {
    return readRows(rs, false);
  }

  /**
   * 결과 집합을 행·컬럼으로 읽는다.
   *
   * @param uniqueNames true 면 겹치는 컬럼 이름을 {@link ResultColumnNames#unique} 로 고유하게 만든다(QueryRunner 분석
   *     조회 — JOIN 의 같은 이름 컬럼 값이 행 Map 에서 덮이지 않게, api PG 경로와 같은 규칙). 규칙은 {@link
   *     ValueConverter#columnName} 변환 <b>뒤</b> 이름에 건다 — {@code REGION} 과 {@code "region"} 은 변환 뒤에야
   *     겹친다.
   */
  static BodyResult readRows(ResultSet rs, boolean uniqueNames) throws SQLException {
    if (rs == null) {
      return new BodyResult(true, 0, List.of(), List.of());
    }
    Columns cols = columns(rs.getMetaData(), uniqueNames);
    List<Map<String, Object>> rows = new ArrayList<>();
    while (rs.next()) {
      rows.add(row(rs, cols));
    }
    return new BodyResult(true, rows.size(), rows, cols.names());
  }

  /**
   * 결과 컬럼 이름과 변환 메타. 이름은 응답 {@code columns} 와 행 Map 의 키로 함께 쓴다.
   *
   * @param names 변환(·고유화)된 컬럼 이름
   * @param metas 컬럼별 변환 메타(타입·정밀도·자릿수)
   */
  record Columns(List<String> names, ValueConverter.ColumnMeta[] metas) {}

  /**
   * 결과 집합 메타에서 컬럼 이름·변환 메타를 한 번만 읽는다 — 칸마다 읽으면 행 수 × 컬럼 수만큼 드라이버를 부른다. 슬롯 스트리밍({@link
   * SlotQueryRunner})도 같은 규칙을 쓴다.
   *
   * @param uniqueNames true 면 겹치는 이름을 {@link ResultColumnNames#unique} 로 고유하게 만든다(변환 뒤 이름 기준)
   */
  static Columns columns(ResultSetMetaData md, boolean uniqueNames) throws SQLException {
    int n = md.getColumnCount();
    List<String> converted = new ArrayList<>(n);
    ValueConverter.ColumnMeta[] metas = new ValueConverter.ColumnMeta[n];
    for (int i = 1; i <= n; i++) {
      converted.add(ValueConverter.columnName(md.getColumnLabel(i)));
      metas[i - 1] = ValueConverter.ColumnMeta.of(md, i);
    }
    // 응답 columns 와 행 Map 의 키가 같은 목록이어야 한다.
    return new Columns(uniqueNames ? ResultColumnNames.unique(converted) : converted, metas);
  }

  /** 결과 집합의 현재 행을 컬럼 이름 → 변환된 값 Map 으로 읽는다. */
  static Map<String, Object> row(ResultSet rs, Columns cols) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 1; i <= cols.metas().length; i++) {
      row.put(cols.names().get(i - 1), ValueConverter.read(rs, i, cols.metas()[i - 1]));
    }
    return row;
  }
}

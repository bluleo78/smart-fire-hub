package com.smartfirehub.dbexecutor.sql;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import java.sql.ResultSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * {@code /execute/query} — 분석 SQL 을 테넌트의 <b>읽기 전용(RO) 계정</b>으로 실행한다.
 *
 * <p>검증은 {@link SqlRunner#validatedBody} 와 같다(가드 + 끝 {@code ;} 제거 — Tibero 는 끝 세미콜론을 거부한다). 첫 키워드가
 * SELECT·WITH 가 아니면 거부하고, 괄호로 시작하는 질의는 {@link TiberoSqlLexer#firstKeyword} 의 기존 동작을 그대로 따른다. 계정 권한이
 * 두 번째 방어선이지만 DML 은 커넥션을 빌리기 전에 여기서 먼저 막는다.
 *
 * <p>{@code SELECT … FOR UPDATE} 는 첫 키워드가 SELECT 여도 거부한다 — Oracle·Tibero 는 SELECT 권한만으로도 행 잠금을
 * 허용하므로 RO 계정 권한이 막아 주지 않는다(항상 ROLLBACK 해도 문장이 도는 동안 잠금을 쥔다).
 *
 * <p>결과 컬럼 이름은 api PG 경로({@code ResultColumnNames.unique})와 같은 규칙으로 고유하게 만든다(WD-85 — JOIN 의 같은 이름
 * 컬럼 값 유실 방지).
 *
 * <p>행 상한은 {@code setMaxRows(maxRows)} 로 드라이버가 끊게 하고, 읽은 행 수가 {@code maxRows} 이상이면 {@code
 * truncated} 다. 읽기만 하지만 트랜잭션을 깨끗이 닫아 커넥션을 반납하려고 성공·실패 모두 ROLLBACK 한다. 대여·기한·실패 처리는 {@link
 * LeasedExecution} 을 쓴다.
 */
@Component
public class QueryRunner {

  static final String QUERY_ONLY = "Tibero 저장소의 분석 SQL 은 조회(SELECT·WITH)만 실행합니다.";
  static final String NO_RO_ACCOUNT = "Tibero 읽기 전용 계정이 준비되지 않았습니다.";
  static final String FOR_UPDATE = "Tibero 저장소의 분석 SQL 에서는 FOR UPDATE 를 쓸 수 없습니다.";
  static final String BAD_REQUEST = "요청 형식이 올바르지 않습니다.";
  static final int MAX_ROWS_LIMIT = 10_000;

  private final int timeoutSeconds;
  private final LeasedExecution execution;
  private final StoreDescriptorClient descriptors;

  public QueryRunner(
      @Qualifier("readOnlyPools") TenantPools readOnlyPools,
      ExecutionTimeouts timeouts,
      StoreDescriptorClient descriptors) {
    this.timeoutSeconds = timeouts.query().timeoutSeconds();
    this.execution = new LeasedExecution(readOnlyPools, descriptors);
    this.descriptors = descriptors;
  }

  /** 조회 시간 초과 문구 — api 가 그대로 보여 주므로 스펙 §3.7 의 시간 초과 안내(「실행 시간 제한(30초)을 넘어 중단했습니다.」)와 같은 모양이다. */
  static String timeoutMessage(int seconds) {
    return "실행 시간 제한(" + seconds + "초)을 넘어 중단했습니다.";
  }

  /**
   * 코드 영역(문자열·주석·큰따옴표 식별자 밖)에 {@code FOR} 다음 단어로 {@code UPDATE} 가 오는지. {@code 'for update'} 리터럴,
   * {@code -- for update} 주석, {@code "FOR UPDATE"} 별칭은 세지 않고, FOR 와 UPDATE 사이에 블록 주석을 끼운 것은 센다(주석은
   * 공백으로 바뀐다).
   *
   * <p>어휘 분석기는 api 복사본과 같게 유지해야 하므로(패키지명만 다름) 새 메서드를 넣지 않고 공개 메서드로 조립한다: {@link
   * TiberoSqlLexer#maskLiteralsAndComments} 가 문자열·주석을 공백으로 바꾼 뒤 남은 {@code "} 는 모두 큰따옴표 식별자의 경계다.
   * 가드를 통과한 본문이라 따옴표가 닫혀 있다.
   */
  static boolean hasForUpdate(String sql) {
    String masked = TiberoSqlLexer.maskLiteralsAndComments(sql);
    String previous = "";
    int i = 0;
    int n = masked.length();
    while (i < n) {
      char c = masked.charAt(i);
      if (c == '"') {
        // 큰따옴표 식별자는 통째로 건너뛴다("" 는 이스케이프 — 다음 따옴표부터 다시 식별자로 이어 건너뛰므로 결과가 같다).
        int close = masked.indexOf('"', i + 1);
        i = close < 0 ? n : close + 1;
        previous = "";
      } else if (TiberoSqlLexer.isIdentChar(c)) {
        int start = i;
        while (i < n && TiberoSqlLexer.isIdentChar(masked.charAt(i))) {
          i++;
        }
        String word = masked.substring(start, i).toUpperCase(java.util.Locale.ROOT);
        if (previous.equals("FOR") && word.equals("UPDATE")) {
          return true;
        }
        previous = word;
      } else {
        // 공백·구두점은 단어 사이를 띄울 뿐이다. 다만 FOR 와 UPDATE 사이에 다른 기호가 오면 이어지지 않는다(예: FOR, UPDATE).
        if (!Character.isWhitespace(c)) {
          previous = "";
        }
        i++;
      }
    }
    return false;
  }

  /**
   * 검증 → RO 풀 대여 → 조회 → 롤백. 오류는 {@code success=false} 로 돌려준다.
   *
   * @param maxRows 1..10000
   * @param deadline 요청 전체 기한(컨트롤러 도착 시점부터). 닫기는 호출자 몫이다.
   */
  public QueryResponse run(StoreDescriptor d, String query, int maxRows, Deadline deadline) {
    if (maxRows < 1 || maxRows > MAX_ROWS_LIMIT) {
      return QueryResponse.failure(BAD_REQUEST, null);
    }
    String body;
    try {
      body = SqlRunner.validatedBody(query, d.schema());
      String first = TiberoSqlLexer.firstKeyword(body);
      if (!first.equals("SELECT") && !first.equals("WITH")) {
        throw new TiberoSqlGuard.Rejected(QUERY_ONLY);
      }
      // SELECT … FOR UPDATE 는 첫 키워드 검사를 통과한다. SELECT 권한만 있는 RO 계정도 행을 잠글 수 있으므로(Oracle·Tibero 규칙)
      // 권한이 막아 주지 않는다 — 커넥션을 빌리기 전에 여기서 거부한다.
      if (hasForUpdate(body)) {
        throw new TiberoSqlGuard.Rejected(FOR_UPDATE);
      }
    } catch (TiberoSqlGuard.Rejected e) {
      return QueryResponse.failure(e.getMessage(), null);
    }
    if (d.ro() == null) {
      // 캐시된 기술자에 RO 가 없으면 그 뒤 RO 계정이 준비돼도 TTL 동안 계속 거부된다 — 접속 실패 경로처럼 캐시를 지워 다음 요청이 새로 받게 한다.
      descriptors.invalidate(d.tenantId());
      return QueryResponse.failure(NO_RO_ACCOUNT, null);
    }

    long startNanos = System.nanoTime();
    return execution.withLease(
        d,
        deadline,
        new LeasedExecution.Failures<>() {
          @Override
          public QueryResponse expiredBeforeBorrow() {
            return QueryResponse.failure(timeoutMessage(timeoutSeconds), null);
          }

          @Override
          public QueryResponse connectFailed() {
            return QueryResponse.failure(LeasedExecution.CONNECT_FAILED, null);
          }

          @Override
          public QueryResponse timedOut() {
            return QueryResponse.failure(timeoutMessage(timeoutSeconds), null);
          }

          @Override
          public QueryResponse dbError(String message, DbError error) {
            return QueryResponse.failure(message, error);
          }

          @Override
          public QueryResponse unexpected() {
            return QueryResponse.failure(LeasedExecution.UNEXPECTED, null);
          }
        },
        c -> {
          SqlRunner.BodyResult out =
              deadline.execute(
                  c,
                  timeoutSeconds,
                  st -> {
                    st.setMaxRows(maxRows);
                    try (ResultSet rs = st.executeQuery(body)) {
                      return SqlRunner.readRows(rs, true);
                    }
                  });
          // 읽기만 했지만 트랜잭션을 닫아 커넥션을 반납한다(실패 경로의 ROLLBACK 은 LeasedExecution 이 한다).
          LeasedExecution.rollbackQuietly(c);
          return new QueryResponse(
              true,
              out.columns(),
              out.rows(),
              out.rowCount(),
              out.rowCount() >= maxRows,
              "SELECT",
              0,
              (System.nanoTime() - startNanos) / 1_000_000L,
              null,
              null);
        });
  }
}

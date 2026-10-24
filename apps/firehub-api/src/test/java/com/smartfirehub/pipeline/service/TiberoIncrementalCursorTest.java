package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.QueryResult;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link TiberoIncrementalCursor} — Tibero 시계 후보 조회 문장과 결과 해석(WD-77 Task 10). */
class TiberoIncrementalCursorTest {

  private final DbExecutorClient client = mock(DbExecutorClient.class);
  private final TiberoIncrementalCursor cursor = new TiberoIncrementalCursor(client);

  private static QueryResult ok(Object value) {
    return new QueryResult(
        true, List.of("candidate"), List.of(Map.of("candidate", value)), 1, false, null, null);
  }

  /** 조회는 RO 창구(executeQuery)로 한 행만 받고, 고정 테이블을 읽는다 — 공개 동의어 V$TRANSACTION 은 일반 계정이 못 푼다. */
  @Test
  void sendsOneRowQueryAgainstTransactionTableWithLeastOfClockAndOldestOpenTransaction() {
    when(client.executeQuery(anyString(), anyInt())).thenReturn(ok("2026-10-24 04:26:39.370111"));

    cursor.captureCandidate();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(client).executeQuery(sql.capture(), eq(1));
    assertThat(sql.getValue())
        .startsWith("SELECT ")
        .contains("LEAST(SYSTIMESTAMP")
        .contains("MIN(START_TIME)")
        .contains("FROM_TZ(")
        .contains("AT TIME ZONE '+00:00'")
        .contains("FROM SYS._VT_TRANSACTION")
        .doesNotContain("V$TRANSACTION");
  }

  /**
   * db-executor 가드는 이 문장 전체와 일치할 때만 SYS._VT_TRANSACTION 을 허용한다(Ruling 11). 양 모듈이 같은 고정값 파일과 대조해
   * 어긋남을 막는다(짝은 db-executor TiberoSqlGuardSystemViewTest).
   */
  @Test
  void candidateSql_matchesSharedFixtureWithDbExecutorGuard() throws Exception {
    String fixture =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(new java.io.File("src/test/resources/fixtures/tibero-cursor-candidate.json"))
            .get("sql")
            .asText();
    assertThat(TiberoIncrementalCursor.CANDIDATE_SQL).isEqualTo(fixture);
  }

  @Test
  void parsesUtcWallClockWithMicroseconds() {
    when(client.executeQuery(anyString(), anyInt())).thenReturn(ok("2026-10-24 04:26:39.370111"));

    assertThat(cursor.captureCandidate())
        .isEqualTo(OffsetDateTime.of(2026, 10, 24, 4, 26, 39, 370_111_000, ZoneOffset.UTC));
  }

  @Test
  void parsesWholeSecondValue() {
    when(client.executeQuery(anyString(), anyInt())).thenReturn(ok("2026-10-24 04:26:39.000000"));

    assertThat(cursor.captureCandidate())
        .isEqualTo(OffsetDateTime.of(2026, 10, 24, 4, 26, 39, 0, ZoneOffset.UTC));
  }

  @Test
  void queryFailure_failsStepWithExecutorMessage() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(
            new QueryResult(false, null, null, 0, false, "권한 없음", new DbError(-1, "denied")));

    assertThatThrownBy(cursor::captureCandidate)
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("Tibero 증분 커서")
        .hasMessageContaining("권한 없음");
  }

  @Test
  void emptyOrUnparseableResult_failsClosed() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(new QueryResult(true, List.of(), List.of(), 0, false, null, null));
    assertThatThrownBy(cursor::captureCandidate).isInstanceOf(ScriptExecutionException.class);

    when(client.executeQuery(anyString(), anyInt())).thenReturn(ok("not a time"));
    assertThatThrownBy(cursor::captureCandidate).isInstanceOf(ScriptExecutionException.class);

    when(client.executeQuery(anyString(), anyInt())).thenReturn(ok(""));
    assertThatThrownBy(cursor::captureCandidate).isInstanceOf(ScriptExecutionException.class);
  }
}

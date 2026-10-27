package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 로컬(실행기 끔) PYTHON 출력의 Tibero 스테이징 적재(WD-78, 스펙 §5.3).
 *
 * <ul>
 *   <li>PG 트랜잭션({@code TransactionTemplate})으로 감싸지 않는다 — Tibero 적재는 Tibero 연결에서 일어나고, 출력 반영은 러너의
 *       옮기기(한 Tibero 트랜잭션)가 한다. 대상은 스테이징 전용 창구({@code insertTempBatch})다.
 *   <li>값은 실행기 켠 경로(db-executor 원격 적재)와 같은 결과가 되게 맞춘다: 오프셋 시각은 UTC 로 환산(JVM 시간대 아님), 비유한 수는 NULL,
 *       중첩 객체·배열은 JSON 문자열.
 *   <li>파싱·변환·실패 문구는 PG 경로({@link LocalPythonOutputLoader#load})와 같다.
 * </ul>
 */
class LocalPythonOutputLoaderTiberoTest {

  private static final String STG = "STG_0123456789ABCDEF01234567";

  private final DataTableRowService rowService = mock(DataTableRowService.class);
  private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
  private final LocalPythonOutputLoader loader = new LocalPythonOutputLoader(rowService, txManager);

  @Test
  void loadStaging_insertsIntoStagingWithoutPgTransaction() {
    long rows =
        loader.loadStaging(
            STG,
            "[{\"code\": \"7\", \"name\": \"a\"}, {\"code\": 8, \"name\": null}]",
            Map.of("code", "INTEGER", "name", "TEXT"));

    assertThat(rows).isEqualTo(2);
    ArgumentCaptor<List<Map<String, Object>>> captor = rowsCaptor();
    verify(rowService)
        .insertTempBatch(eq(STG), eq(List.of("code", "name")), captor.capture(), isNull());
    assertThat(captor.getValue().get(0)).containsEntry("code", 7L).containsEntry("name", "a");
    // PG 트랜잭션 매니저는 손대지 않는다.
    verifyNoInteractions(txManager);
    // 스테이징 창구 하나만 — 실제 테이블용 insertBatch(ID·_UPDATED_AT 을 씀)는 부르지 않는다.
    verifyNoMoreInteractions(rowService);
  }

  @Test
  void loadStaging_kstOffsetTimestamp_becomesUtcLocalDateTime_likeDbExecutor() {
    loader.loadStaging(STG, "[{\"ts\": \"2026-10-24T09:30:00+09:00\"}]", Map.of("ts", "TIMESTAMP"));

    ArgumentCaptor<List<Map<String, Object>>> captor = rowsCaptor();
    verify(rowService).insertTempBatch(eq(STG), anyList(), captor.capture(), isNull());
    // JVM 시간대(맥은 KST)와 무관하게 UTC 벽시계 — db-executor LoadValues 와 같은 순간.
    assertThat(captor.getValue().get(0).get("ts")).isEqualTo(LocalDateTime.of(2026, 10, 24, 0, 30));
  }

  @Test
  void loadStaging_nonFiniteNumbersBecomeNull_nestedValuesBecomeJson() {
    // 타입 맵이 있으면 모든 키가 맵에 있어야 한다(WD-211 컬럼 확인) — 타입 없는 값(Infinity·중첩)은 맵 없이 따로 본다.
    loader.loadStaging(
        STG,
        "[{\"amount\": \"NaN\", \"ok\": \"1.5\"}]",
        Map.of("amount", "DECIMAL", "ok", "DECIMAL"));
    loader.loadStaging(STG, "[{\"raw\": Infinity, \"obj\": {\"a\": 1}, \"arr\": [1, 2]}]", null);

    ArgumentCaptor<List<Map<String, Object>>> captor = rowsCaptor();
    verify(rowService, times(2)).insertTempBatch(eq(STG), anyList(), captor.capture(), isNull());
    Map<String, Object> typed = captor.getAllValues().get(0).get(0);
    assertThat(typed.get("amount")).isNull();
    assertThat(typed.get("ok")).isEqualTo(new BigDecimal("1.5"));
    Map<String, Object> untyped = captor.getAllValues().get(1).get(0);
    assertThat(untyped.get("raw")).isNull();
    assertThat(untyped.get("obj")).isEqualTo("{\"a\":1}");
    assertThat(untyped.get("arr")).isEqualTo("[1,2]");
  }

  @Test
  void loadStaging_booleans_stayBoolean_likeRemotePath() {
    // M4: 불리언은 타입이 있든 없든 Boolean 으로 남는다 — 바인딩(TiberoValues.bind)이 1/0 으로 넣는다. 원격 경로도 JSON bool 을
    // db-executor
    // LoadValues 가 Boolean 으로 두고 같은 규칙(1/0)으로 바인딩하므로 같은 값이 저장된다.
    // 타입 없는 컬럼은 타입 맵이 없을 때만 생긴다(맵이 있으면 모든 키가 맵에 있어야 한다 — WD-211).
    loader.loadStaging(STG, "[{\"typed\": \"yes\"}]", Map.of("typed", "BOOLEAN"));
    loader.loadStaging(STG, "[{\"untyped\": true}]", null);

    ArgumentCaptor<List<Map<String, Object>>> captor = rowsCaptor();
    verify(rowService, times(2)).insertTempBatch(eq(STG), anyList(), captor.capture(), isNull());
    assertThat(captor.getAllValues().get(0).get(0)).containsEntry("typed", Boolean.TRUE);
    assertThat(captor.getAllValues().get(1).get(0)).containsEntry("untyped", Boolean.TRUE);
  }

  @Test
  void loadStaging_integerBeyond64Bits_staysExactBigInteger_knownDifferenceFromRemote() {
    // M4(알려진 차이): 64비트를 넘는 INTEGER 는 로컬에서 정확한 BigInteger 로 바인딩된다(물리 컬럼 NUMBER(19) 를 넘으면 Tibero 오류).
    // 원격 경로는 db-executor LoadValues 가 longValueExact 로 요청 전체를 실패시킨다(2ba6f765) — 두 경로 모두 값을 바꿔 저장하지
    // 않는다.
    // 차이는 Long.MAX_VALUE 초과 ~ NUMBER(19) 상한 사이 값이 로컬에서는 저장되고 원격에서는 실패한다는 것뿐이다.
    loader.loadStaging(STG, "[{\"n\": \"12345678901234567890\"}]", Map.of("n", "INTEGER"));

    ArgumentCaptor<List<Map<String, Object>>> captor = rowsCaptor();
    verify(rowService).insertTempBatch(eq(STG), anyList(), captor.capture(), isNull());
    assertThat(captor.getValue().get(0).get("n"))
        .isEqualTo(new java.math.BigInteger("12345678901234567890"));
  }

  @Test
  void loadStaging_notJson_failsBeforeLoad_likePg() {
    // stdout 이 JSON 이 아니면 PG 경로와 같이 적재 전에 한국어 안내로 실패한다(WD-210) — 조용한 0행 완료가 아니다.
    assertThatThrownBy(() -> loader.loadStaging(STG, "hello", Map.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageStartingWith(PythonStepErrorMessages.PREFIX)
        .hasMessageContaining("JSON 이 아니어서");
    verifyNoInteractions(rowService, txManager);
  }

  @Test
  void loadStaging_emptyStdout_loadsNothing() {
    assertThat(loader.loadStaging(STG, "", Map.of())).isZero();
    verifyNoInteractions(rowService, txManager);
  }

  @Test
  void loadStaging_insertFailure_usesSameUserMessageAsPgPath() {
    doThrow(new IllegalStateException("JDBC-8033: 테이블 없음"))
        .when(rowService)
        .insertTempBatch(anyString(), anyList(), anyList(), any());

    assertThatThrownBy(() -> loader.loadStaging(STG, "[{\"a\": 1}]", null))
        .isInstanceOf(ScriptExecutionException.class)
        // 원문(Tibero 오류 문장)은 로그에만 — PG 경로와 같은 일반 안내 문구다(WD-211).
        .hasMessage(PythonStepErrorMessages.PREFIX + PythonStepErrorMessages.LOAD_FAILED_MESSAGE);
  }

  /** Tibero JDBC 길이 오류는 PG 데이터 오류와 같은 적재 안내를 내며 원문을 노출하지 않는다. */
  @Test
  void loadStaging_tiberoValueTooLarge_usesDataErrorMessage() {
    doThrow(
            new UncategorizedSQLException(
                "insert",
                "INSERT INTO SECRET",
                new SQLException("JDBC-11048 secret", null, -11048)))
        .when(rowService)
        .insertTempBatch(anyString(), anyList(), anyList(), any());

    assertThatThrownBy(() -> loader.loadStaging(STG, "[{\"a\": 1}]", null))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(PythonStepErrorMessages.loadDataError("글자 수 제한을 넘는 값이 있습니다"));
  }

  @Test
  void loadStaging_selectStarRows_dropSystemKeys_likePgLoadsThem() {
    // 코드리뷰 1: SELECT * 모양 행(id·import_id·created_at·_updated_at 포함)도 적재된다. PG 출력 테이블은 이 시스템 컬럼을
    // 물리적으로
    // 가져 그대로 받지만, Tibero 스테이징은 사용자 컬럼만 있다(시스템 컬럼은 옮길 때 채운다) — 네 키만 빼고 넣는다. _updated_at 은
    // 이름 검증(^[a-z]...)에도 걸리므로 빼지 않으면 스테이징에 닿기 전에 실패한다.
    long rows =
        loader.loadStaging(
            STG,
            "[{\"id\": 1, \"name\": \"a\", \"import_id\": null,"
                + " \"created_at\": \"2026-10-24T00:00:00\", \"_updated_at\": \"2026-10-24T00:00:00\"},"
                + " {\"id\": 2, \"name\": \"b\", \"import_id\": 9,"
                + " \"created_at\": \"2026-10-24T00:00:00\", \"_updated_at\": \"2026-10-24T00:00:00\"}]",
            Map.of("name", "TEXT"));

    assertThat(rows).isEqualTo(2);
    ArgumentCaptor<List<Map<String, Object>>> captor = rowsCaptor();
    verify(rowService).insertTempBatch(eq(STG), eq(List.of("name")), captor.capture(), isNull());
    assertThat(captor.getValue()).containsExactly(Map.of("name", "a"), Map.of("name", "b"));
  }

  @Test
  void loadStaging_nonSystemExtraKey_failsBeforeLoad_likePg() {
    // 시스템 이름이 아닌 여분 키는 걸러내지 않는다 — PG 경로와 같이 적재 전 컬럼 확인(WD-211)에서 그 키를 짚어 실패하고 스테이징은 건드리지 않는다.
    assertThatThrownBy(
            () ->
                loader.loadStaging(
                    STG, "[{\"id\": 1, \"name\": \"a\", \"foo\": 1}]", Map.of("name", "TEXT")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(PythonStepErrorMessages.unknownColumn("foo"));
    verifyNoInteractions(rowService);
  }

  @Test
  void loadStaging_onlySystemKeys_failsWithClearMessage() {
    assertThatThrownBy(
            () -> loader.loadStaging(STG, "[{\"id\": 1, \"created_at\": \"2026-10-24\"}]", null))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageStartingWith(PythonStepErrorMessages.PREFIX)
        .hasMessageContaining("시스템 컬럼");
    verifyNoInteractions(rowService);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<List<Map<String, Object>>> rowsCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
  }
}

package com.smartfirehub.securitylevel.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.QueryResult;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import com.smartfirehub.pipeline.service.executor.ExecutorResponseTooLargeException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tibero 분석 조회 서비스의 검증 순서(조회 전용·스키마·위치 파라미터)와 오류 문구 매핑을 고정한다(WD-76). */
class TiberoQueryServiceTest {

  private DbExecutorClient client;
  private TiberoQueryService service;

  @BeforeEach
  void setUp() {
    client = mock(DbExecutorClient.class);
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.validator()).thenReturn(SqlValidator.forTiberoSchema("GENIA_T5"));
    when(schema.isTibero()).thenReturn(true);
    service = new TiberoQueryService(schema, client);
  }

  private static QueryResult failure(String error, DbError dbError) {
    return new QueryResult(false, List.of(), List.of(), 0, false, error, dbError);
  }

  @Test
  void isTibero_delegatesToSchema() {
    assertThat(service.isTibero()).isTrue();
  }

  @Test
  void run_success_mapsResult() {
    when(client.executeQuery("SELECT A FROM GENIA_T5.T", 100))
        .thenReturn(
            new QueryResult(true, List.of("A"), List.of(Map.of("A", 1)), 1, false, null, null));

    TiberoQueryService.Outcome out = service.run("SELECT A FROM GENIA_T5.T", 100);

    assertThat(out.error()).isNull();
    assertThat(out.columns()).containsExactly("A");
    assertThat(out.rows()).hasSize(1);
    assertThat(out.truncated()).isFalse();
    assertThat(out.executionTimeMs()).isGreaterThanOrEqualTo(0);
  }

  /** 행 상한은 실행기가 받는 1..10000 으로 보정되어 전달된다(0 → 1, 50000 → 10000). */
  @Test
  void run_clampsMaxRows() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(new QueryResult(true, List.of("A"), List.of(), 0, false, null, null));

    service.run("SELECT A FROM GENIA_T5.T", 0);
    service.run("SELECT A FROM GENIA_T5.T", 50000);

    verify(client).executeQuery("SELECT A FROM GENIA_T5.T", 1);
    verify(client).executeQuery("SELECT A FROM GENIA_T5.T", 10000);
  }

  /** WITH … SELECT 는 조회로 인정된다. */
  @Test
  void run_withSelect_isQuery() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(new QueryResult(true, List.of("A"), List.of(), 0, false, null, null));

    TiberoQueryService.Outcome out =
        service.run("WITH c AS (SELECT 1 A FROM DUAL) SELECT A FROM c", 10);

    assertThat(out.error()).isNull();
    verify(client).executeQuery(anyString(), anyInt());
  }

  /** 괄호로 시작하는 (SELECT …) UNION … 도 조회로 인정된다. */
  @Test
  void run_parenthesizedUnion_isQuery() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(new QueryResult(true, List.of("A"), List.of(), 0, false, null, null));

    TiberoQueryService.Outcome out =
        service.run("(SELECT 1 A FROM DUAL) UNION (SELECT 2 A FROM DUAL)", 10);

    assertThat(out.error()).isNull();
    verify(client).executeQuery(anyString(), anyInt());
  }

  @Test
  void run_dml_rejectedWithoutCallingClient() {
    TiberoQueryService.Outcome out = service.run("UPDATE GENIA_T5.T SET A = 1", 10);

    assertThat(out.error()).isEqualTo("Tibero 저장소의 분석 SQL 은 조회(SELECT·WITH)만 실행합니다.");
    verifyNoInteractions(client);
  }

  @Test
  void run_unqualifiedTable_rejectedByValidator() {
    TiberoQueryService.Outcome out = service.run("SELECT * FROM orders", 10);

    assertThat(out.error()).contains("테이블 참조에 스키마가 없습니다");
    verifyNoInteractions(client);
  }

  @Test
  void run_positionalParameter_rejected() {
    TiberoQueryService.Outcome out = service.run("SELECT A FROM GENIA_T5.T WHERE A = $1", 10);

    assertThat(out.error()).contains("위치 파라미터 $1");
    verify(client, never()).executeQuery(anyString(), anyInt());
  }

  @Test
  void run_statementCanceled_mapsToTimeoutMessage() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(failure("x", new DbError(-12040, "JDBC-12040:Statement canceled.")));

    TiberoQueryService.Outcome out = service.run("SELECT A FROM GENIA_T5.T", 10);

    assertThat(out.error())
        .isEqualTo("실행 시간 제한(30초)을 넘어 중단했습니다.\n원인: JDBC-12040:Statement canceled.");
  }

  @Test
  void run_otherDbError_stripsOfNullOnly() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(
            failure(
                "x",
                new DbError(-8026, "JDBC-8026:Invalid identifier.\nat line 1, column 8 of null:")));

    TiberoQueryService.Outcome out = service.run("SELECT A FROM GENIA_T5.T", 10);

    assertThat(out.error())
        .isEqualTo("Tibero 오류: JDBC-8026:Invalid identifier.\nat line 1, column 8:");
  }

  /**
   * 테이블 없음(-8033 — 이름 오류이거나 RO 계정에 권한이 없어 안 보이는 테이블)은 파이프라인과 같은 "참조한 테이블이 없습니다" 안내 + 원인(스펙 §3.9).
   * 분석 SQL 은 감싸지 않으므로 줄 번호는 그대로이고 " of null" 만 뗀다.
   */
  @Test
  void run_tableNotFound_usesMissingTableGuidance() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(
            failure(
                "x",
                new DbError(
                    -8033,
                    "JDBC-8033:Specified schema object was not found.\nat line 1, column 15 of null:")));

    TiberoQueryService.Outcome out = service.run("SELECT A FROM GENIA_T5.NOPE", 10);

    assertThat(out.error())
        .isEqualTo(
            "SQL 에서 참조한 테이블이 없습니다. 테이블 이름과 스키마 한정자를 확인하세요.\n"
                + "원인: JDBC-8033:Specified schema object was not found.\nat line 1, column 15:");
    // 문장은 파이프라인 안내와 같은 출처다(문구가 두 벌로 갈리지 않는다).
    assertThat(out.error())
        .startsWith(
            com.smartfirehub.pipeline.service.PipelineSqlErrorMessages.tiberoMissingTableGuide(
                -8033));
  }

  @Test
  void run_failureWithoutDbError_passesErrorThrough() {
    // db-executor 의 시간 초과(기한 경과)는 dbError 없이 이미 사용자 문구로 온다 — 그대로 전달한다.
    when(client.executeQuery(anyString(), anyInt()))
        .thenReturn(failure("실행 시간 제한(30초)을 넘어 중단했습니다.", null));

    assertThat(service.run("SELECT A FROM GENIA_T5.T", 10).error())
        .isEqualTo("실행 시간 제한(30초)을 넘어 중단했습니다.");
  }

  @Test
  void run_failureWithoutReason_usesFallbackMessage() {
    when(client.executeQuery(anyString(), anyInt())).thenReturn(failure(null, null));

    assertThat(service.run("SELECT A FROM GENIA_T5.T", 10).error()).isEqualTo("Tibero 조회에 실패했습니다.");
  }

  @Test
  void run_connectFailure_usesExceptionMessage() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenThrow(new ScriptExecutionException(DbExecutorClient.CONNECT_FAILURE_MESSAGE));

    assertThat(service.run("SELECT A FROM GENIA_T5.T", 10).error())
        .isEqualTo("Tibero 실행기에 연결하지 못했습니다.");
  }

  @Test
  void run_tooLarge_usesExceptionMessage() {
    when(client.executeQuery(anyString(), anyInt()))
        .thenThrow(new ExecutorResponseTooLargeException(1024, new RuntimeException()));

    assertThat(service.run("SELECT A FROM GENIA_T5.T", 10).error()).contains("결과가 너무 큽니다");
  }
}

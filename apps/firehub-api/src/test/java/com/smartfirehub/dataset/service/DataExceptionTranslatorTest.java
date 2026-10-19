package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * {@link DataExceptionTranslator} 단위 테스트(WD-96) — SQLState 별 사유 선택, 래핑·연쇄 예외 탐색, DB 원문 비노출, 데이터 오류가
 * 아닌 예외는 null(호출부가 기존 일반 문구 유지). 실제 jOOQ/Spring 래핑 체인과 임포트 실패 문구 전체는 {@code
 * DataImportPhysicalLimitIntegrationTest} 가 검증한다.
 */
class DataExceptionTranslatorTest {

  /** 서버가 보내는 구조화 오류 — 필드 코드 S(심각도)·C(SQLState)·M(메시지)·c(컬럼), NUL 구분. */
  private static PSQLException pgError(String state, String message, String column) {
    String raw =
        "SERROR\0C" + state + "\0M" + message + "\0" + (column != null ? "c" + column + "\0" : "");
    return new PSQLException(new ServerErrorMessage(raw));
  }

  @Test
  void stringTruncation_22001_takesLimitFromServerMessage_withoutDbText() {
    String msg =
        DataExceptionTranslator.describe(
            new RuntimeException(
                "SQL [INSERT INTO data.\"secret_tbl\"]",
                pgError("22001", "value too long for type character varying(10)", null)));
    assertThat(msg)
        .isEqualTo("글자 수 제한(최대 10자)을 넘는 값이 있습니다")
        .doesNotContain("secret_tbl")
        .doesNotContain("character varying");
  }

  @Test
  void limitIsNotTakenFromUserValueOrSqlText_inOuterBatchMessage() {
    // 바깥 BatchUpdateException 메시지에는 SQL 과 바인딩 값이 섞인다 — 사용자 값 "character varying(99)" 에 속으면 안 된다.
    BatchUpdateException batch =
        new BatchUpdateException(
            "Batch entry 0 INSERT INTO data.t (code) VALUES ('character varying(99)') was aborted",
            "22001",
            new int[0]);
    batch.setNextException(
        pgError("22001", "value too long for type character varying(10)", "code"));

    String msg = DataExceptionTranslator.describe(new IllegalStateException(batch));

    // 서버 오류(다음 예외)를 우선해 실제 상한(10)과 컬럼명을 쓴다.
    assertThat(msg).contains("'code' 컬럼에 글자 수 제한(최대 10자)을 넘는 값이 있습니다").doesNotContain("99");
  }

  @Test
  void plainSqlExceptionWithoutServerMessage_omitsLimitRatherThanGuessing() {
    String msg =
        DataExceptionTranslator.describe(
            new SQLException("value too long for type character varying(99)", "22001"));
    assertThat(msg).contains("글자 수 제한을 넘는 값이 있습니다").doesNotContain("99");
  }

  @Test
  void eachDataExceptionState_hasItsOwnReason() {
    assertThat(DataExceptionTranslator.describe(new SQLException("x", "22003")))
        .contains("숫자 범위를 넘는 값이 있습니다(소수 컬럼은 정수부 최대 12자리)");
    assertThat(DataExceptionTranslator.describe(new SQLException("x", "22P02")))
        .contains("컬럼 타입에 맞지 않는 형식의 값");
    assertThat(DataExceptionTranslator.describe(new SQLException("x", "22008")))
        .contains("날짜·시간 값");
    assertThat(DataExceptionTranslator.describe(new SQLException("x", "22021"))).contains("NUL 문자");
    assertThat(DataExceptionTranslator.describe(new SQLException("x", "22012")))
        .contains("저장할 수 없는 값이 있습니다");
  }

  @Test
  void findsDataException_inJdbcNextExceptionChain() {
    BatchUpdateException batch = new BatchUpdateException("batch failed", "08000", new int[0]);
    batch.setNextException(new SQLException("next", "22003"));
    assertThat(DataExceptionTranslator.describe(new IllegalStateException(batch)))
        .contains("숫자 범위");
  }

  @Test
  void nonDataErrors_returnNull_soGenericMessageIsKept() {
    assertThat(DataExceptionTranslator.describe(new SQLException("dup", "23505"))).isNull();
    assertThat(DataExceptionTranslator.describe(new SQLException("no state"))).isNull();
    assertThat(DataExceptionTranslator.describe(new RuntimeException("plain"))).isNull();
  }
}

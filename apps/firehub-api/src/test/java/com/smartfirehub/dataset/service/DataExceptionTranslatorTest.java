package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

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

  /**
   * (WD-266) Tibero 는 SQLState 대신 벤더 코드로 데이터 오류를 알린다 — 대응표로 옮겨 PG 와 같은 사유 문장을 낸다. 드라이버 원문은 사유에 싣지
   * 않는다.
   */
  @ParameterizedTest
  @CsvSource({
    "-5111,22003",
    "-5074,22P02",
    "-5113,22007",
    "-5114,22008",
    "-11048,22001",
    "12899,22001"
  })
  void tiberoVendorCode_givesSameReasonAsPg(int code, String state) {
    String msg =
        DataExceptionTranslator.describe(
            new DataIntegrityViolationException(
                "x", new SQLException("JDBC" + code + ": secret", null, code)));
    assertThat(msg)
        .isEqualTo(DataExceptionTranslator.describeDataError(state, null, null))
        .doesNotContain("secret");
  }

  /** (WD-266) 데이터 오류가 아닌 Tibero 코드(중복 키·NOT NULL)는 사유가 없다 — 호출부의 기존 분류(409 등)를 탄다. */
  @ParameterizedTest
  @ValueSource(ints = {-10007, -10005})
  void tiberoNonDataCode_returnsNull(int code) {
    assertThat(
            DataExceptionTranslator.describe(
                new DataIntegrityViolationException(
                    "x", new SQLException("JDBC" + code + ":", null, code))))
        .isNull();
  }

  /**
   * (WD-266) Tibero 의 -5074 는 SQLState 가 22 계열이 아닌 ERRDT 이고 -5114 는 PG 의 22008 이 아닌 22007 로 온다(실측
   * 2026-10-28) — 벤더 코드 대응표가 일반 22xxx 물러서기보다 먼저다.
   */
  @Test
  void tiberoVendorCode_winsOverNonPgSqlState() {
    assertThat(DataExceptionTranslator.describe(new SQLException("JDBC-5111:", "22000", -5111)))
        .isEqualTo(DataExceptionTranslator.describeDataError("22003", null, null));
  }

  /** (WD-266) PG 드라이버 예외는 벤더 코드가 늘 0 이라 Tibero 판정에 걸리지 않는다 — PG 결과는 그대로다. */
  @Test
  void pgExceptionsWithZeroVendorCode_areUnchanged() {
    assertThat(DataExceptionTranslator.describe(new PSQLException("m", PSQLState.UNIQUE_VIOLATION)))
        .isNull();
    assertThat(
            DataExceptionTranslator.describe(new BatchUpdateException("m", "23505", 0, new int[0])))
        .isNull();
  }

  /**
   * (WD-266) "데이터 오류" 판정의 두 원천이 어긋나지 않는다 — 사유는 {@link TiberoDialect#sqlState} 가 22 계열로 옮기는지로, 400
   * 응답은 번역기가 {@link TiberoDialect#INTEGRITY_VIOLATIONS} 로 {@code DataIntegrityViolationException} 을
   * 만드는지로 정한다. 대응표에만 22 매핑을 새로 넣고 집합에 빠뜨리면 사유 문장은 있는데 예외가 400 으로 가지 않으므로(500), 22 로 옮겨지는 코드가 모두 집합에
   * 있는지 본다.
   *
   * <p>대조할 코드는 {@link TiberoDialect} 의 공개 코드 집합 전부를 리플렉션으로 모은다 — 새 집합을 추가해도 이 목록을 손으로 늘릴 필요가 없다.
   */
  @Test
  void tiberoDataErrorRule_every22MappedCodeIsIntegrityViolation() throws IllegalAccessException {
    Set<Integer> known = new HashSet<>();
    for (Field f : TiberoDialect.class.getFields()) {
      if (Modifier.isStatic(f.getModifiers()) && Set.class.isAssignableFrom(f.getType())) {
        // 오류 코드 집합(Set<Integer>)만 모은다 — 예약어 같은 문자열 집합은 건너뛴다.
        for (Object code : (Set<?>) f.get(null)) {
          if (code instanceof Integer c) {
            known.add(c);
          }
        }
      }
    }
    // NotFound 는 코드 목록을 공개하지 않아 알려진 테이블 없음 코드(Oracle 942, Tibero -8033)로 대조한다.
    known.add(942);
    known.add(-8033);
    for (int code : known) {
      if (TiberoDialect.sqlState(code).startsWith("22")) {
        assertThat(TiberoDialect.INTEGRITY_VIOLATIONS)
            .as("SQLState 22 로 옮겨지는 코드 %d 는 INTEGRITY_VIOLATIONS 에 있어야 400 이 된다", code)
            .contains(code);
      }
    }
  }
}

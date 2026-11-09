package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link ColumnPhysicalType} 단위 테스트(WD-98) — 엄격(행 API)·관대(임포트) 모드의 DECIMAL 경계, 0·극단 지수 판정, 정확한
 * BIGINT 변환. 실제 DB 저장값과의 대조는 DatasetDataServiceTest·DataImportPhysicalLimitIntegrationTest 가 한다.
 */
class ColumnPhysicalTypeTest {

  private static Object strict(String v) {
    return ColumnPhysicalType.enforceLimitsStrict(
        new BigDecimal(v), "DECIMAL", null, DataStoreBackend.POSTGRESQL);
  }

  private static Object lenient(String v) {
    return ColumnPhysicalType.enforceLimits(
        new BigDecimal(v), "DECIMAL", null, DataStoreBackend.POSTGRESQL);
  }

  /** 0 은 scale·지수와 무관하게 0 으로 통과하고, 끝 0 만 붙은 값은 값이 같아 엄격 모드도 통과한다. */
  @Test
  void strictDecimal_zeroAndTrailingZeros_pass() {
    for (String zero :
        new String[] {"0", "0E-10", "0.0000000000", "0E+5", "-0.0", "0E-999999999"}) {
      assertThat(strict(zero)).as(zero).isEqualTo(new BigDecimal("0.000000"));
    }
    assertThat(strict("1.5000000")).isEqualTo(new BigDecimal("1.500000"));
    assertThat(strict("-999999999999.999999")).isEqualTo(new BigDecimal("-999999999999.999999"));
  }

  /** 엄격 모드는 반올림이 필요한 값을 거부한다 — 극단 지수도 큰 수를 펼치지 않고 바로 판정한다. */
  @Test
  void strictDecimal_roundingNeeded_rejected() {
    for (String v : new String[] {"0.0000001", "-0.0000001", "1.1234567", "1E-999999999"}) {
      assertThatThrownBy(() -> strict(v))
          .as(v)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("소수점 아래는 최대 6자리까지 입력할 수 있습니다");
    }
    assertThatThrownBy(() -> strict("1E+999999999")).hasMessageStartingWith("정수부는 최대 12자리");
  }

  /** 관대 모드(임포트)는 같은 값을 PG 규칙(HALF_UP)대로 반올림한다 — 엄격 모드 추가가 임포트 동작을 바꾸지 않는다. */
  @Test
  void lenientDecimal_roundsLikePostgres() {
    assertThat(lenient("0.0000005")).isEqualTo(new BigDecimal("0.000001"));
    assertThat(lenient("0.0000001")).isEqualTo(new BigDecimal("0.000000"));
    assertThat(lenient("1E-999999999")).isEqualTo(new BigDecimal("0.000000"));
    assertThat(lenient("0E-10")).isEqualTo(new BigDecimal("0.000000"));
  }

  /** 엄격 모드 VARCHAR 는 공백뿐인 초과분도 거부, 관대 모드는 잘라 저장한다. */
  @Test
  void varchar_trailingSpaceOverflow_strictRejects_lenientTrims() {
    assertThatThrownBy(
            () ->
                ColumnPhysicalType.enforceLimitsStrict(
                    "abc  ", "VARCHAR", 3, DataStoreBackend.POSTGRESQL))
        .hasMessage("최대 3자까지 입력할 수 있습니다(입력 5자)");
    assertThat(ColumnPhysicalType.enforceLimits("abc  ", "VARCHAR", 3, DataStoreBackend.POSTGRESQL))
        .isEqualTo("abc");
  }

  /**
   * WD-659 — 길이 미지정 VARCHAR 의 값 한도는 저장소 기본 길이다. Tibero(VARCHAR2(4000 CHAR))는 300자를 엄격·관대 모드 모두 받고
   * 4001자는 거부한다. PG(VARCHAR(255))는 예전처럼 256자를 거부(엄격)하거나, 초과분이 공백뿐이면 255자로 자른다(관대).
   */
  @Test
  void varchar_withoutLength_usesBackendDefaultLength() {
    String v300 = "a".repeat(300);
    assertThat(
            ColumnPhysicalType.enforceLimitsStrict(v300, "VARCHAR", null, DataStoreBackend.TIBERO))
        .isEqualTo(v300);
    assertThat(ColumnPhysicalType.enforceLimits(v300, "VARCHAR", null, DataStoreBackend.TIBERO))
        .isEqualTo(v300);
    String v4001 = "a".repeat(4001);
    assertThatThrownBy(
            () ->
                ColumnPhysicalType.enforceLimitsStrict(
                    v4001, "VARCHAR", null, DataStoreBackend.TIBERO))
        .hasMessage("최대 4000자까지 입력할 수 있습니다(입력 4001자)");
    assertThatThrownBy(
            () -> ColumnPhysicalType.enforceLimits(v4001, "VARCHAR", null, DataStoreBackend.TIBERO))
        .hasMessage("최대 4000자까지 입력할 수 있습니다(입력 4001자)");

    // PG 는 그대로 255
    assertThatThrownBy(
            () ->
                ColumnPhysicalType.enforceLimitsStrict(
                    "a".repeat(256), "VARCHAR", null, DataStoreBackend.POSTGRESQL))
        .hasMessage("최대 255자까지 입력할 수 있습니다(입력 256자)");
    assertThatThrownBy(
            () ->
                ColumnPhysicalType.enforceLimits(
                    v300, "VARCHAR", null, DataStoreBackend.POSTGRESQL))
        .hasMessage("최대 255자까지 입력할 수 있습니다(입력 300자)");
    assertThat(
            ColumnPhysicalType.enforceLimits(
                "a".repeat(255) + "   ", "VARCHAR", null, DataStoreBackend.POSTGRESQL))
        .isEqualTo("a".repeat(255));
  }

  /** WD-659 — 명시한 길이는 저장소 기본 길이보다 우선한다(Tibero 에서도 VARCHAR(10) 은 11자 거부). */
  @Test
  void varchar_explicitLength_winsOverBackendDefault() {
    assertThatThrownBy(
            () ->
                ColumnPhysicalType.enforceLimitsStrict(
                    "a".repeat(11), "VARCHAR", 10, DataStoreBackend.TIBERO))
        .hasMessage("최대 10자까지 입력할 수 있습니다(입력 11자)");
    assertThat(
            ColumnPhysicalType.enforceLimitsStrict(
                "a".repeat(300), "VARCHAR", 1000, DataStoreBackend.POSTGRESQL))
        .isEqualTo("a".repeat(300));
  }

  /** WD-659 — 저장소 없이 부르면 PG 기본값으로 조용히 떨어지지 않고 즉시 실패한다. */
  @Test
  void enforceLimits_withoutBackend_failsFast() {
    assertThatThrownBy(() -> ColumnPhysicalType.enforceLimits("a", "VARCHAR", null, null))
        .isInstanceOf(NullPointerException.class);
  }

  /** 정수 변환은 잘라 내거나 포화시키지 않는다 — 소수부가 0 인 값·지수 표기는 정수, 그 밖은 형식/범위 오류. */
  @Test
  void toExactBigint_exactOrRejected() {
    assertThat(ColumnPhysicalType.toExactBigint(new BigDecimal("1E+3"), "1e3")).isEqualTo(1000L);
    assertThat(ColumnPhysicalType.toExactBigint(new BigDecimal("5.0"), "5.0")).isEqualTo(5L);
    assertThat(ColumnPhysicalType.toExactBigint(new BigDecimal("0E-5"), "0")).isZero();
    assertThat(ColumnPhysicalType.toExactBigint(new BigDecimal("9007199254740993"), "x"))
        .isEqualTo(9007199254740993L);
    assertThat(ColumnPhysicalType.toExactBigint(new BigDecimal(Long.MIN_VALUE), "min"))
        .isEqualTo(Long.MIN_VALUE);

    assertThatThrownBy(() -> ColumnPhysicalType.toExactBigint(new BigDecimal("1.9"), "1.9"))
        .hasMessage("정수 형식이 아닙니다: 1.9");
    assertThatThrownBy(
            () ->
                ColumnPhysicalType.toExactBigint(
                    new BigDecimal("9223372036854775808"), "9223372036854775808"))
        .hasMessage(
            "정수 범위를 벗어났습니다: 9223372036854775808 (허용 범위: -9223372036854775808 ~ 9223372036854775807)");
    assertThatThrownBy(
            () -> ColumnPhysicalType.toExactBigint(new BigDecimal("1E+999999999"), "1e999999999"))
        .hasMessageStartingWith("정수 범위를 벗어났습니다");
  }

  /** WD-569·WD-636 — VARCHAR 길이 안내는 저장소별 상한과 입력값을 받아 같은 한국어 문장으로 만든다(테이블명·필드명 길이 문구와 같은 꼴). */
  @Test
  void varcharLengthOutOfRangeMessage_usesGivenMaxAndShowsInput() {
    assertThat(
            ColumnPhysicalType.varcharLengthOutOfRangeMessage(
                ColumnPhysicalType.MAX_VARCHAR_LENGTH, 10001))
        .isEqualTo("문자열(VARCHAR) 필드 길이는 1~10000 사이여야 합니다(입력 10001).");
    assertThat(ColumnPhysicalType.varcharLengthOutOfRangeMessage(4000, 5000))
        .isEqualTo("문자열(VARCHAR) 필드 길이는 1~4000 사이여야 합니다(입력 5000).");
    assertThat(ColumnPhysicalType.varcharLengthOutOfRangeMessage(4000, 0))
        .isEqualTo("문자열(VARCHAR) 필드 길이는 1~4000 사이여야 합니다(입력 0).");
  }

  /** WD-569 — 알 수 없는 타입 안내는 입력값을 붙이되, 제어 문자는 공백으로 바꾸고 50자를 넘으면 자른다. */
  @Test
  void unknownDataTypeMessage_sanitizesAndCapsEcho() {
    assertThat(ColumnPhysicalType.unknownDataTypeMessage("NOPE"))
        .isEqualTo("알 수 없는 데이터 타입입니다: NOPE");
    assertThat(ColumnPhysicalType.unknownDataTypeMessage("A\nB\tC"))
        .isEqualTo("알 수 없는 데이터 타입입니다: A B C");
    assertThat(ColumnPhysicalType.unknownDataTypeMessage("x".repeat(60)))
        .isEqualTo("알 수 없는 데이터 타입입니다: " + "x".repeat(50) + "…");
    assertThat(ColumnPhysicalType.unknownDataTypeMessage("x".repeat(50)))
        .isEqualTo("알 수 없는 데이터 타입입니다: " + "x".repeat(50));
  }

  /**
   * WD-636 — 유니코드 서식 문자(양방향 재정렬 U+202E·영폭 공백 U+200B·BOM U+FEFF)와 줄/문단 구분자(U+2028·U+2029)도 공백으로 바꾼다.
   * 응답 문구에 섞여 토스트 글자 순서를 뒤집거나 보이지 않는 글자를 숨기지 못하게 한다.
   */
  @Test
  void unknownDataTypeMessage_replacesUnicodeFormatAndSeparatorChars() {
    String message =
        ColumnPhysicalType.unknownDataTypeMessage("A\u202EB\u200BC\uFEFFD\u2028E\u2029F");
    assertThat(message).isEqualTo("알 수 없는 데이터 타입입니다: A B C D E F");
  }

  /** WD-636 — 빈 타입(null·공백)은 「알 수 없는 타입: 」(뒤가 빔) 대신 타입을 입력하라는 안내를 준다. */
  @Test
  void unknownDataTypeMessage_blank_returnsRequiredMessage() {
    assertThat(ColumnPhysicalType.unknownDataTypeMessage(null))
        .isEqualTo(DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE);
    assertThat(ColumnPhysicalType.unknownDataTypeMessage(""))
        .isEqualTo(DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE);
    assertThat(ColumnPhysicalType.unknownDataTypeMessage("  \t "))
        .isEqualTo(DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE);
  }

  /** WD-636 — 입력 경계의 타입 정규화: 앞뒤 공백을 지우고 대문자로(Locale.ROOT). null 은 그대로 둔다(필수 검증이 따로 막는다). */
  @Test
  void normalizeDataType_trimsAndUppercases() {
    assertThat(ColumnPhysicalType.normalizeDataType(" varchar ")).isEqualTo("VARCHAR");
    assertThat(ColumnPhysicalType.normalizeDataType("integer")).isEqualTo("INTEGER");
    assertThat(ColumnPhysicalType.normalizeDataType("TEXT")).isEqualTo("TEXT");
    assertThat(ColumnPhysicalType.normalizeDataType("   ")).isEmpty();
    assertThat(ColumnPhysicalType.normalizeDataType(null)).isNull();
  }

  /** WD-636 — 요청 record 가 만들어질 때 타입이 정규화된다(생성·필드 추가·타입 변경·파이프라인 TEMP 등 모든 경로 공통). */
  @Test
  void requestRecords_normalizeDataType() {
    assertThat(new DatasetColumnRequest("a", null, " varchar ", 10, true, false, null).dataType())
        .isEqualTo("VARCHAR");
    assertThat(new AddColumnRequest("a", null, "date", null, true, false, null).dataType())
        .isEqualTo("DATE");
    assertThat(new UpdateColumnRequest(null, null, "boolean", null, null, null, null).dataType())
        .isEqualTo("BOOLEAN");
    assertThat(new UpdateColumnRequest(null, null, null, null, null, null, null).dataType())
        .isNull();
  }

  /**
   * WD-636 리뷰 — 정규화는 정준 타입일 때만 대문자로 바꾼다. 그 밖의 값은 앞뒤 공백만 지우고 원문을 둬야 「알 수 없는 타입」 안내가 사용자가 보낸 값을 그대로
   * 보여 준다(「straße」가 「STRASSE」로 바뀌지 않게). 별칭(bigint·NUMERIC)도 정준 타입이 아니다.
   */
  @Test
  void normalizeDataType_keepsNonCanonicalInputAsSent() {
    assertThat(ColumnPhysicalType.normalizeDataType("straße")).isEqualTo("straße");
    assertThat(ColumnPhysicalType.normalizeDataType(" bigint ")).isEqualTo("bigint");
    assertThat(ColumnPhysicalType.normalizeDataType("NUMERIC")).isEqualTo("NUMERIC");
    assertThat(new AddColumnRequest("a", null, "straße", null, true, false, null).dataType())
        .isEqualTo("straße");
    assertThat(ColumnPhysicalType.unknownDataTypeMessage("straße"))
        .isEqualTo("알 수 없는 데이터 타입입니다: straße");
  }

  /** WD-636 리뷰 — 앞뒤의 유니코드 공백(NBSP U+00A0·전각 공백 U+3000)도 지운다. trim() 은 U+0020 이하만 지운다. */
  @Test
  void normalizeDataType_stripsUnicodeSpaces() {
    assertThat(ColumnPhysicalType.normalizeDataType("\u00A0varchar\u3000")).isEqualTo("VARCHAR");
    assertThat(ColumnPhysicalType.normalizeDataType("\u3000\u00A0")).isEmpty();
  }

  /** WD-636 리뷰 — 정준 타입 집합은 앱 전체가 switch 하는 8개뿐이다(저장소 별칭 BIGINT·NUMERIC 제외). */
  @Test
  void validDataTypes_areCanonicalOnly() {
    assertThat(ColumnPhysicalType.VALID_DATA_TYPES)
        .containsExactlyInAnyOrder(
            "TEXT", "VARCHAR", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP", "GEOMETRY");
  }

  /** WD-636 리뷰 — 「타입 필요」 문구의 단일 출처는 ColumnPhysicalType 이고, DTO 상수는 같은 값을 가리킨다. */
  @Test
  void dataTypeRequiredMessage_singleSource() {
    assertThat(DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE)
        .isEqualTo(ColumnPhysicalType.DATA_TYPE_REQUIRED_MESSAGE)
        .isEqualTo("컬럼 데이터 타입을 입력하세요.");
  }

  /** WD-636 리뷰 — 저장소별 VARCHAR 상한의 단일 출처는 DataStoreBackend 다(PG 10000·Tibero 4000). */
  @Test
  void maxVarcharLength_singleSourceInDataStoreBackend() {
    assertThat(ColumnPhysicalType.MAX_VARCHAR_LENGTH)
        .isEqualTo(DataStoreBackend.POSTGRESQL.maxVarcharLength())
        .isEqualTo(10000);
    assertThat(TiberoDialect.MAX_VARCHAR)
        .isEqualTo(DataStoreBackend.TIBERO.maxVarcharLength())
        .isEqualTo(4000);
  }

  /**
   * WD-655 — 길이 미지정 VARCHAR 의 실제 길이는 저장소마다 다르다(PG 255, Tibero 4000 — {@code
   * TiberoDialect.columnDdl} 과 같은 값). 테넌트 밖 길이 검사의 상한은 모든 저장소 상한 중 가장 넓은 값으로 계산한다.
   */
  @Test
  void defaultVarcharLength_perBackend_andWidestLimit() {
    assertThat(DataStoreBackend.POSTGRESQL.effectiveVarcharLength(null))
        .isEqualTo(ColumnPhysicalType.DEFAULT_VARCHAR_LENGTH)
        .isEqualTo(255);
    assertThat(DataStoreBackend.TIBERO.effectiveVarcharLength(null)).isEqualTo(4000);
    assertThat(TiberoDialect.columnDdl("VARCHAR", null)).isEqualTo("VARCHAR2(4000 CHAR)");
    assertThat(DataStoreBackend.TIBERO.effectiveVarcharLength(100)).isEqualTo(100);
    assertThat(ColumnPhysicalType.WIDEST_VARCHAR_LENGTH).isEqualTo(10000);
  }
}

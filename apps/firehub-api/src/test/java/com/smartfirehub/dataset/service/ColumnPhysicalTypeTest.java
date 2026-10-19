package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link ColumnPhysicalType} 단위 테스트(WD-98) — 엄격(행 API)·관대(임포트) 모드의 DECIMAL 경계, 0·극단 지수 판정, 정확한
 * BIGINT 변환. 실제 DB 저장값과의 대조는 DatasetDataServiceTest·DataImportPhysicalLimitIntegrationTest 가 한다.
 */
class ColumnPhysicalTypeTest {

  private static Object strict(String v) {
    return ColumnPhysicalType.enforceLimitsStrict(new BigDecimal(v), "DECIMAL", null);
  }

  private static Object lenient(String v) {
    return ColumnPhysicalType.enforceLimits(new BigDecimal(v), "DECIMAL", null);
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
    assertThatThrownBy(() -> ColumnPhysicalType.enforceLimitsStrict("abc  ", "VARCHAR", 3))
        .hasMessage("최대 3자까지 입력할 수 있습니다(입력 5자)");
    assertThat(ColumnPhysicalType.enforceLimits("abc  ", "VARCHAR", 3)).isEqualTo("abc");
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
}

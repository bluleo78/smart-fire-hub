package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * WD-105 — 정밀도 판정 공용 헬퍼 단위 테스트. 직접 경로(jOOQ: BigDecimal·Long·BigInteger)와 executor 경로(Jackson 이 파싱한
 * Integer·Long·BigInteger·Double, pydantic 이 문자열로 준 NUMERIC)의 값 모양을 모두 넣어 본다.
 */
class JsSafeNumbersTest {

  @Test
  void integerBoundary_isPlusMinus2Pow53Minus1() {
    assertThat(JsSafeNumbers.isUnsafeInteger(9_007_199_254_740_991L)).isFalse();
    assertThat(JsSafeNumbers.isUnsafeInteger(-9_007_199_254_740_991L)).isFalse();
    assertThat(JsSafeNumbers.isUnsafeInteger(9_007_199_254_740_992L)).isTrue();
    assertThat(JsSafeNumbers.isUnsafeInteger(-9_007_199_254_740_992L)).isTrue();
    assertThat(JsSafeNumbers.isUnsafeInteger(Long.MIN_VALUE)).isTrue();
    assertThat(JsSafeNumbers.isUnsafeInteger(BigInteger.valueOf(9_007_199_254_740_991L))).isFalse();
    assertThat(JsSafeNumbers.isUnsafeInteger(new BigInteger("-9007199254740992"))).isTrue();
    assertThat(JsSafeNumbers.isUnsafeInteger(new BigInteger("123456789012345678901234567890")))
        .isTrue();
    // Integer·문자열은 정수 판정 대상이 아니다
    assertThat(JsSafeNumbers.isUnsafeInteger(Integer.MAX_VALUE)).isFalse();
    assertThat(JsSafeNumbers.isUnsafeInteger("9007199254740993")).isFalse();
  }

  @Test
  void decimal_lossyOnlyWhenDoubleRoundTripDiffers() {
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("123456789012.123456"))).isTrue();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("-987654321098.000001"))).isTrue();
    // 끝자리 0 만 다른 값·짧은 소수·0E-6 은 double 로 같은 값이 나온다
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("1.500000"))).isFalse();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("100.000000"))).isFalse();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("0.1"))).isFalse();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("0E-6"))).isFalse();
    // double 범위 밖(Infinity)은 예외 없이 깎임으로 본다
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("1E+400"))).isTrue();
  }

  @Test
  void decimal_fastPathBoundaryAt15SignificantDigits() {
    // 15자리(DBL_DIG) 이하는 빠른 통과로 안 깎임 — 실제 왕복도 같은 결과여야 한다
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("123456789012.345"))).isFalse();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("999999999999999"))).isFalse();
    // 16·17자리는 실제 왕복 비교를 탄다 — 깎이는 값은 깎임으로(빠른 통과를 17자리로 넓히면 여기서 잡힌다)
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("99999999999999.99"))).isTrue();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("12345678901234.567"))).isTrue();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("9007199254740993"))).isTrue();
    // 16자리라도 double 왕복이 정확한 값은 안 깎임(16자리 = 무조건 깎임이 아니다)
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("123456789012.3456"))).isFalse();
    // 큰 지수는 빠른 통과 밖 — 정상 범위면 실제 왕복으로 안 깎임, 범위 밖이면 Infinity 로 깎임
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("1E+305"))).isFalse();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("1E-305"))).isFalse();
    assertThat(JsSafeNumbers.isLossyDecimal(new BigDecimal("1E+400"))).isTrue();
  }

  @Test
  void losesPrecision_coversArraysAndIgnoresOtherTypes() {
    assertThat(JsSafeNumbers.losesPrecision(null)).isFalse();
    assertThat(JsSafeNumbers.losesPrecision("123456789012.123456"))
        .isFalse(); // executor 경로 NUMERIC
    assertThat(JsSafeNumbers.losesPrecision(1.2345678901234567e11)).isFalse(); // Double 은 이미 double
    assertThat(JsSafeNumbers.losesPrecision(42)).isFalse();
    assertThat(JsSafeNumbers.losesPrecision(List.of(1L, 2L))).isFalse();
    assertThat(JsSafeNumbers.losesPrecision(Arrays.asList(1, 9_007_199_254_740_993L))).isTrue();
    assertThat(
            JsSafeNumbers.losesPrecision(
                new Object[] {new Object[] {new BigDecimal("1.5")}, new Object[] {null}}))
        .isFalse();
    assertThat(
            JsSafeNumbers.losesPrecision(
                new Object[] {
                  new Object[] {new BigDecimal("1.5")},
                  new Object[] {new BigDecimal("123456789012.123456")}
                }))
        .isTrue();
  }

  @Test
  void exactText_matchesJsonStringifyShapeWithExactDigits() {
    assertThat(JsSafeNumbers.exactText(new BigDecimal("123456789012.123450")))
        .isEqualTo("123456789012.12345");
    assertThat(JsSafeNumbers.exactText(new BigDecimal("1E+21")))
        .isEqualTo("1000000000000000000000");
    assertThat(JsSafeNumbers.exactText(9_007_199_254_740_993L)).isEqualTo("9007199254740993");
    assertThat(
            JsSafeNumbers.exactText(
                new Object[] {
                  new Object[] {new BigDecimal("123456789012.123456"), null},
                  new Object[] {new BigDecimal("2.000")}
                }))
        .isEqualTo("[[123456789012.123456,null],[2]]");
  }

  @Test
  void exactValues_isSparseAndEmptyWhenNothingIsLossy() {
    Map<String, Object> safe = new HashMap<>();
    safe.put("a", new BigDecimal("1.5"));
    safe.put("b", 42L);
    safe.put("c", null);
    Map<String, Object> lossy = new HashMap<>();
    lossy.put("a", new BigDecimal("123456789012.123456"));
    lossy.put("b", 42L);
    lossy.put("c", "text");

    assertThat(JsSafeNumbers.exactValues(List.of(safe, safe))).isEmpty();
    assertThat(JsSafeNumbers.exactValues(null)).isEmpty();
    assertThat(JsSafeNumbers.exactValues(List.of(safe, lossy)))
        .isEqualTo(Map.of(1, Map.of("a", "123456789012.123456")));
  }
}

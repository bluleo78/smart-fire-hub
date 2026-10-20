package com.smartfirehub.global.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 결과 값이 JSON 숫자로 나가 브라우저(JS double)에서 다른 값으로 바뀌는지 판정하고, 그때 보여 줄 원문 텍스트를 만드는 공용 헬퍼(WD-105).
 *
 * <p>왜: 브라우저·ai-agent 의 JSON 파서는 숫자를 IEEE double 로 읽으므로 NUMERIC {@code 123456789012.123456} 은
 * {@code 123456789012.12346} 으로, 2^53 을 넘는 BIGINT {@code 9007199254740993} 은 {@code
 * 9007199254740992} 로 보인다. 데이터셋 행·SQL 탭 응답({@code DatasetRowValuesSerializer}, WD-101·104)과 분석 쿼리
 * 결과 표({@code AnalyticsQueryResponse.exactValues}, WD-105)가 같은 정수 경계를 쓰도록 판정을 이 한 곳에 둔다.
 *
 * <ul>
 *   <li>{@link Long}·{@link BigInteger} → ±(2^53-1)(JS {@code Number.MAX_SAFE_INTEGER}) 밖이면 깎인다.
 *   <li>{@link BigDecimal} → double 로 바꾼 뒤 가장 짧은 10진 표기({@link BigDecimal#valueOf(double)})가 원래 값과
 *       다르면 깎인다. {@code compareTo} 로 비교하므로 {@code 1.50}·{@code 100.000000} 처럼 끝자리 0 만 다른 값은 깎이지
 *       않는다. JS 의 {@code String(number)} 도 같은 "가장 짧은 왕복 표기"를 쓰므로(JDK 19+ Double.toString), 판정이 화면
 *       표시와 맞는다.
 *   <li>배열({@code Object[]}·{@link List}) → 원소 중 하나라도 깎이면 깎인다(다차원은 재귀).
 *   <li>그 밖(Integer·Double·String·날짜 등) → 깎이지 않는다. executor 경로는 NUMERIC 을 이미 문자열로 주므로(pydantic
 *       Decimal 직렬화) 여기서 대상이 아니다.
 * </ul>
 */
public final class JsSafeNumbers {

  /** JS Number.MAX_SAFE_INTEGER(2^53-1) — 이 범위 안의 정수만 JSON 숫자로 내보내도 값이 보존된다. */
  public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  /** BigInteger 비교용 ±(2^53-1) 경계 — bitLength 로 판정하면 -2^53 에서 한 칸 어긋나므로 양쪽 값과 직접 비교한다. */
  private static final BigInteger MAX_SAFE_BIG = BigInteger.valueOf(MAX_SAFE_INTEGER);

  private static final BigInteger MIN_SAFE_BIG = BigInteger.valueOf(-MAX_SAFE_INTEGER);

  private JsSafeNumbers() {}

  /** 정수 값이 JS 안전 정수 범위(±(2^53-1)) 밖인지. Long·BigInteger 가 아니면 false. */
  public static boolean isUnsafeInteger(Object value) {
    if (value instanceof Long l) {
      // Math.abs 는 Long.MIN_VALUE 에서 넘치므로 양쪽 경계를 따로 비교한다.
      return l > MAX_SAFE_INTEGER || l < -MAX_SAFE_INTEGER;
    }
    if (value instanceof BigInteger big) {
      return big.compareTo(MAX_SAFE_BIG) > 0 || big.compareTo(MIN_SAFE_BIG) < 0;
    }
    return false;
  }

  /** 소수 값이 double 왕복에서 다른 값이 되는지(= 브라우저에 다른 숫자로 보이는지). */
  public static boolean isLossyDecimal(BigDecimal decimal) {
    // 빠른 통과: 유효숫자 15자리 이하(DBL_DIG=15)인 10진수는 double 로 바꿨다 되돌리면 항상 같은 값이 된다(IEEE 754 binary64 의
    // 보장). 단 십진 지수가 정상 범위(약 1e±308) 안이어야 하므로 여유를 두고 |지수| < 300 으로 제한한다. 결과 표 대부분의 소수가
    // 여기서 끝나 double·BigDecimal 객체를 만들지 않는다. 16자리 이상·큰 지수는 아래 실제 왕복 비교를 탄다.
    int exponent = decimal.precision() - decimal.scale() - 1;
    if (decimal.precision() <= 15 && Math.abs(exponent) < 300) {
      return false;
    }
    double d = decimal.doubleValue();
    // 1e400 같은 범위 밖 값은 Infinity 가 된다 — valueOf(Infinity) 는 예외이므로 먼저 깎임으로 본다.
    if (Double.isInfinite(d)) {
      return true;
    }
    return BigDecimal.valueOf(d).compareTo(decimal) != 0;
  }

  /** 셀 값(스칼라 또는 배열)이 JSON 숫자로 나가면 브라우저에서 다른 값으로 보이는지. */
  public static boolean losesPrecision(Object value) {
    if (value instanceof BigDecimal decimal) {
      return isLossyDecimal(decimal);
    }
    if (value instanceof Object[] || value instanceof List<?>) {
      for (Object element : asList(value)) {
        if (losesPrecision(element)) {
          return true;
        }
      }
      return false;
    }
    return isUnsafeInteger(value);
  }

  /**
   * 행 목록에서 정밀도를 잃는 칸만 골라 "행 인덱스 → {컬럼명: 원문}" 희소 맵을 만든다. 잃는 칸이 없으면 빈 맵.
   *
   * <p>행 값 자체는 바꾸지 않는다 — 차트·대시보드는 같은 rows 를 {@code typeof number} 로 쓰므로(WD-105 A안) 화면 표 전용 원문을 옆에
   * 따로 싣는다.
   */
  public static Map<Integer, Map<String, String>> exactValues(List<Map<String, Object>> rows) {
    Map<Integer, Map<String, String>> exact = new HashMap<>();
    if (rows == null) {
      return exact;
    }
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> row = rows.get(i);
      if (row == null) {
        continue;
      }
      for (Map.Entry<String, Object> cell : row.entrySet()) {
        if (losesPrecision(cell.getValue())) {
          exact
              .computeIfAbsent(i, k -> new HashMap<>())
              .put(cell.getKey(), exactText(cell.getValue()));
        }
      }
    }
    return exact;
  }

  /**
   * 값의 원문 텍스트 — 화면 표가 그 칸에 그대로 보일 문자열.
   *
   * <p>소수는 {@code stripTrailingZeros().toPlainString()}(데이터셋 행 응답과 같은 모양, 지수 표기 없음), 정수는 10진 문자열.
   * 배열은 웹 결과 표가 객체 칸을 그리는 {@code JSON.stringify} 와 같은 모양({@code [1.5,null,[2]]})이되 숫자만 원문 자릿수로 쓴다.
   * PG 배열은 원소 타입이 하나라 정밀도를 잃는 배열의 원소는 숫자·null·중첩 배열뿐이다.
   */
  static String exactText(Object value) {
    StringBuilder sb = new StringBuilder();
    appendExact(value, sb);
    return sb.toString();
  }

  private static void appendExact(Object value, StringBuilder sb) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof BigDecimal decimal) {
      sb.append(plainDecimal(decimal));
    } else if (value instanceof Object[] || value instanceof List<?>) {
      sb.append('[');
      boolean first = true;
      for (Object element : asList(value)) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        appendExact(element, sb);
      }
      sb.append(']');
    } else {
      // Long·BigInteger·Integer 등 정수는 toString 이 곧 10진 원문이다.
      sb.append(value);
    }
  }

  /**
   * 소수의 원문 표기 — 끝자리 0 은 예전 표시(JS number → String)처럼 지우고 지수 표기는 쓰지 않는다(1.500000 → "1.5", 100.000000
   * → "100", 0E-6 → "0"). 데이터셋 행 응답(WD-101)과 분석 결과 표 원문(WD-105)이 같은 모양을 쓴다.
   */
  public static String plainDecimal(BigDecimal decimal) {
    return decimal.stripTrailingZeros().toPlainString();
  }

  /**
   * 배열 칸 값({@code Object[]} 또는 {@link List})을 List 로 — Object[] 는 Arrays.asList(복사 없는 뷰)로 한 경로로
   * 다룬다.
   */
  public static List<?> asList(Object value) {
    return value instanceof Object[] array ? Arrays.asList(array) : (List<?>) value;
  }
}

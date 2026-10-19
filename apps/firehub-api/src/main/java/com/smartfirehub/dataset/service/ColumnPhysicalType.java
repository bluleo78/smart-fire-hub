package com.smartfirehub.dataset.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 데이터셋 컬럼의 물리 타입 한도(VARCHAR 길이·NUMERIC 정밀도·NUL 금지)와, 값을 그 한도에 맞춰 검사·정규화하는 규칙의 단일 출처.
 *
 * <p>물리 DDL({@link DataTableService#mapDataType})과 값 검사(임포트 검증 등)가 같은 상수를 봐야 "검증 통과 == INSERT 성공"이
 * 어긋나지 않는다. 값을 쓰는 어느 계층에서도 쓸 수 있게 dataset 계층에 둔다.
 */
public final class ColumnPhysicalType {

  /** 길이 미지정 VARCHAR 의 물리 타입 길이 — {@code VARCHAR(255)}. */
  public static final int DEFAULT_VARCHAR_LENGTH = 255;

  /** DECIMAL 의 물리 타입 {@code NUMERIC(18,6)} 전체 자릿수. */
  public static final int DECIMAL_PRECISION = 18;

  /** DECIMAL 의 물리 타입 {@code NUMERIC(18,6)} 소수 자릿수. */
  public static final int DECIMAL_SCALE = 6;

  /** DECIMAL 정수부 허용 자릿수(18 - 6 = 12). */
  public static final int DECIMAL_INTEGER_DIGITS = DECIMAL_PRECISION - DECIMAL_SCALE;

  /** PostgreSQL 텍스트 타입이 담을 수 없는 NUL(U+0000) 문자 — 바인딩/INSERT 가 22021 로 실패한다. */
  public static final char NUL = '\u0000';

  private static final BigDecimal DECIMAL_ZERO = BigDecimal.ZERO.setScale(DECIMAL_SCALE);

  private ColumnPhysicalType() {}

  /**
   * 형식 변환을 마친 값이 물리 컬럼에 들어가는지 검사하고, PostgreSQL 이 실제로 저장할 값으로 정규화해 돌려준다. 위반 시 사유를 담은 {@link
   * IllegalArgumentException}.
   *
   * <p>파일 임포트용 관대 모드 — PG 가 오류 없이 받는 값(초과분이 공백뿐인 VARCHAR, 소수 7자리 이상)은 PG 와 같은 규칙으로 잘라·반올림해 받는다. 사람이
   * 직접 입력하는 행 API 는 {@link #enforceLimitsStrict} 를 쓴다.
   *
   * @param maxLength VARCHAR 길이(그 밖의 타입은 무시)
   * @return VARCHAR 는 공백 초과분을 자른 문자열, DECIMAL 은 scale 6 BigDecimal, 그 밖은 입력 그대로
   */
  public static Object enforceLimits(Object value, String dataType, Integer maxLength) {
    return enforceLimits(value, dataType, maxLength, false);
  }

  /**
   * {@link #enforceLimits} 의 엄격 모드 — 저장값이 입력값과 달라지는 경우를 정규화하지 않고 거부한다(WD-98).
   *
   * <p>행 추가·수정처럼 사람이 직접 친 값은 "성공" 뒤 다른 값이 저장되면 알아챌 수 없다(예: {@code 0.0000001} → {@code 0.000000}).
   * 그래서 소수부 7자리 이상(끝 0 제외)과 VARCHAR 길이 초과(초과분이 공백뿐이어도)를 거부한다. 웹 행 폼의 판정과 같다.
   */
  public static Object enforceLimitsStrict(Object value, String dataType, Integer maxLength) {
    return enforceLimits(value, dataType, maxLength, true);
  }

  private static Object enforceLimits(
      Object value, String dataType, Integer maxLength, boolean strict) {
    switch (dataType) {
      case "TEXT" -> rejectNul(value);
      case "VARCHAR" -> {
        rejectNul(value);
        if (value instanceof String s) {
          return fitVarchar(s, maxLength != null ? maxLength : DEFAULT_VARCHAR_LENGTH, strict);
        }
      }
      case "DECIMAL" -> {
        if (value instanceof BigDecimal bd) {
          return normalizeDecimal(bd, strict);
        }
      }
      default -> {
        // 그 밖의 타입은 형식 변환 단계의 검사로 충분하다(INTEGER 범위는 Long 파싱이 거부).
      }
    }
    return value;
  }

  /** NUL 이 든 문자열을 INSERT 가 22021 로 실패하기 전에 거부한다. */
  private static void rejectNul(Object value) {
    if (value instanceof String s && s.indexOf(NUL) >= 0) {
      throw new IllegalArgumentException("저장할 수 없는 문자(NUL)가 포함되어 있습니다");
    }
  }

  /**
   * VARCHAR(limit) 에 맞춘다 — PG 처럼 문자 수(코드포인트)로 세고, 초과분이 전부 공백이면 잘라 저장하는 PG 규칙을 미리 적용한다. 엄격 모드는 공백
   * 초과분도 거부한다.
   */
  private static String fitVarchar(String s, int limit, boolean strict) {
    // 코드포인트 수 ≤ UTF-16 길이이므로 UTF-16 길이가 상한 이내면 세지 않고 통과(대부분의 값이 타는 핫 패스).
    if (s.length() <= limit) {
      return s;
    }
    int length = s.codePointCount(0, s.length());
    if (length <= limit) {
      return s;
    }
    if (strict) {
      throw varcharTooLong(limit, length);
    }
    int cut = s.offsetByCodePoints(0, limit);
    for (int i = cut; i < s.length(); i++) {
      // PG 는 초과분이 모두 공백(U+0020, 탭 제외)일 때만 오류 없이 자른다(SQL 표준).
      if (s.charAt(i) != ' ') {
        throw varcharTooLong(limit, length);
      }
    }
    // PG 에 맡기지 않고 여기서 자른다 — 검증·업서트 키 비교·삽입이 모두 같은 "실제 저장값"을 보게 하기 위함.
    return s.substring(0, cut);
  }

  private static IllegalArgumentException varcharTooLong(int limit, int length) {
    return new IllegalArgumentException("최대 " + limit + "자까지 입력할 수 있습니다(입력 " + length + "자)");
  }

  /**
   * 소수 6자리로 반올림 정규화한 뒤 정수부 12자리 초과를 거부한다 — PG 도 "반올림 → 정밀도 검사" 순서라 같은 값으로 판정해야 경계가 맞는다. 엄격 모드는 반올림이
   * 필요한 값(끝 0 을 뺀 소수부 7자리 이상)을 거부하므로 반올림이 일어나지 않는다.
   */
  private static BigDecimal normalizeDecimal(BigDecimal bd, boolean strict) {
    // precision - scale = 정수부 자릿수(10^(n-1) ≤ |x| < 10^n). 양 끝은 setScale 없이 판정한다 — "1E999999999"·
    // "1E-999999999" 를 펼치면 거대한 수를 할당한다. 반올림은 절댓값을 줄이지 않으므로 이미 13자리 이상이면 바로 거부하고,
    // |x| < 10^-7 이면 반올림 결과가 0 이다. HALF_UP(0.5 는 0 에서 먼 쪽, 음수도 절댓값 기준)은 PG numeric 반올림과 같다
    // (DB 대조 테스트로 고정). 그 사이에서는 지수 차이가 입력 자릿수 정도라 setScale 비용이 입력 길이에 비례한다.
    int integerDigits = bd.precision() - bd.scale();
    if (integerDigits > DECIMAL_INTEGER_DIGITS) {
      throw integerPartTooLong(false);
    }
    // 엄격 모드: 끝 0 을 뺀 소수부가 7자리 이상이면(반올림이 필요하면) 거부한다. 0 은 scale 이 커도 0 으로, 극단 지수도
    // unscaled 값이 작아 stripTrailingZeros 가 큰 수를 펼치지 않는다. 통과한 값은 아래 반올림이 값을 바꾸지 않는다.
    if (strict && bd.stripTrailingZeros().scale() > DECIMAL_SCALE) {
      throw new IllegalArgumentException("소수점 아래는 최대 " + DECIMAL_SCALE + "자리까지 입력할 수 있습니다");
    }
    if (integerDigits < -DECIMAL_SCALE) {
      return DECIMAL_ZERO;
    }
    BigDecimal normalized = bd.setScale(DECIMAL_SCALE, RoundingMode.HALF_UP);
    if (normalized.precision() - normalized.scale() > DECIMAL_INTEGER_DIGITS) {
      // 원래 값은 12자리 안인데 반올림으로 13자리가 된 경우(예: 999999999999.9999995)
      throw integerPartTooLong(true);
    }
    return normalized;
  }

  /**
   * 정수 값을 INTEGER 의 물리 타입 BIGINT(long)로 정확히 바꾼다 — 소수부가 있으면(끝 0 제외) 형식 오류, BIGINT 범위 밖이면 범위 오류. 잘라
   * 내거나 포화시키지 않는다.
   *
   * @param shown 안내 문구에 넣을 원래 입력 값
   */
  public static long toExactBigint(BigDecimal value, Object shown) {
    if (value.stripTrailingZeros().scale() > 0) {
      throw new IllegalArgumentException("정수 형식이 아닙니다: " + shown);
    }
    try {
      return value.longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "정수 범위를 벗어났습니다: " + shown + " (허용 범위: " + Long.MIN_VALUE + " ~ " + Long.MAX_VALUE + ")");
    }
  }

  private static IllegalArgumentException integerPartTooLong(boolean byRounding) {
    String message = "정수부는 최대 " + DECIMAL_INTEGER_DIGITS + "자리까지 입력할 수 있습니다";
    if (byRounding) {
      message += "(소수 " + DECIMAL_SCALE + "자리로 반올림하면 정수부가 13자리가 됩니다)";
    }
    return new IllegalArgumentException(message);
  }
}

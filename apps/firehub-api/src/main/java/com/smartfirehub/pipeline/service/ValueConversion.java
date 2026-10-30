package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.service.ColumnPhysicalType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 파이프라인 출력 값 변환 규칙(WD-431) — PYTHON 스텝 로컬 적재({@link LocalPythonOutputLoader})와 API_CALL 스텝({@code
 * JsonResponseParser})이 함께 쓰는 단일 기준.
 *
 * <p><b>규칙.</b> 컬럼 타입으로 바꿀 수 없는 값은 NULL 로 넣거나 행을 건너뛰지 않고 {@link Failed} 로 스텝을 실패시킨다(호출자가 행·칸·값·타입을
 * 짚는 한국어 문구를 붙인다 — 예전엔 조용히 NULL·건너뛰기라 REPLACE 출력이 망가져도 「완료」였다).
 *
 * <ul>
 *   <li>null 은 NULL. 숫자·불리언·날짜 타입의 빈 문자열(공백뿐 포함)도 NULL({@link #BLANK_IS_NULL_TYPES}, 데이터 임포트와 같다).
 *   <li>불리언: true/false/1/0/yes/no(대소문자·공백 무시), JSON 불리언, 숫자 1·0 만 받는다('Y'·'예' 는 실패 — 예전엔 거짓).
 *   <li>정수: 64비트 범위의 정수 값만. 정수 값인 실수 표기(3.0·"3.0"·"1e2")는 받고, 소수부(1.5)·범위 밖(1e20)은 실패(잘라 넣지 않는다).
 *   <li>오류 문구의 값은 Python {@code str()} 표기({@link #pyStr})를 {@link #VALUE_EXCERPT_MAX} 코드 포인트로 자른 것.
 * </ul>
 *
 * <p>실행기(apps/firehub-executor {@code app/services/value_conversion.py})가 같은 규칙·같은 라벨을 들고 있고,
 * db-executor {@code LoadValues} 는 모듈이 달라 따로 들고 있다 — 이 클래스가 기준이며 바꾸면 함께 바꾼다.
 */
public final class ValueConversion {

  /** 오류 문구에 인용하는 값의 최대 길이(코드 포인트 — Python 문자열 슬라이스와 같은 단위). 넘으면 잘라 말줄임표를 붙인다. */
  public static final int VALUE_EXCERPT_MAX = 50;

  /** 참으로 받는 문자열(앞뒤 공백 제거·소문자 기준). 데이터 임포트와 같다. */
  private static final Set<String> TRUE_TOKENS = Set.of("true", "1", "yes");

  /** 거짓으로 받는 문자열(앞뒤 공백 제거·소문자 기준). 데이터 임포트와 같다. */
  private static final Set<String> FALSE_TOKENS = Set.of("false", "0", "no");

  /** 빈 문자열을 NULL 로 보는 타입(별칭 포함, 대문자). 텍스트·지오메트리·알 수 없는 타입은 빈 문자열을 그대로 둔다. */
  public static final Set<String> BLANK_IS_NULL_TYPES =
      Set.of(
          "INTEGER",
          "DECIMAL",
          "NUMERIC",
          "FLOAT",
          "DOUBLE",
          "BOOLEAN",
          "DATE",
          "TIMESTAMP",
          "DATETIME");

  /** 유한 십진수 문자열 문법(부호·밑줄·소수점·지수 — 정수 표기 포함). Python {@code int()}·{@code Decimal()} 의 유한수와 같다. */
  private static final Pattern DECIMAL_LITERAL =
      Pattern.compile(
          "[+-]?(?:[0-9](?:_?[0-9])*(?:\\.(?:[0-9](?:_?[0-9])*)?)?|\\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?");

  private ValueConversion() {}

  /** 값 하나를 컬럼 타입으로 바꾸지 못했다 — 행·칸 정보는 호출자가 붙인다(이 예외는 값만 안다). */
  public static final class Failed extends RuntimeException {
    public Failed() {
      super(null, null, false, false);
    }

    /** 파서 예외(NumberFormatException·DateTimeParseException 등)를 감싸 원인을 로그에 남긴다. */
    public Failed(Throwable cause) {
      super(null, cause, false, false);
    }
  }

  /**
   * 이 타입에서 NULL 로 볼 빈 문자열인가.
   *
   * @param upperType 대문자 타입 이름(호출자가 한 번만 대문자로 바꿔 넘긴다)
   */
  public static boolean isBlankNull(Object value, String upperType) {
    return value instanceof String s && s.isBlank() && BLANK_IS_NULL_TYPES.contains(upperType);
  }

  /** 불리언 엄격 변환 — 받지 않는 값이면 {@link Failed}. */
  public static boolean toBooleanStrict(Object value) {
    Boolean b = parseBoolean(value);
    if (b == null) {
      throw new Failed();
    }
    return b;
  }

  /** 불리언 해석. 받을 수 없는 값이면 null(빈 문자열은 호출자가 먼저 NULL 로 거른다). */
  public static Boolean parseBoolean(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      // 1.0 처럼 값이 1·0 과 같으면 받는다(Python 의 1.0 == 1). NaN·무한대는 BigDecimal 이 거부한다.
      try {
        BigDecimal d = new BigDecimal(n.toString());
        if (d.compareTo(BigDecimal.ONE) == 0) {
          return true;
        }
        if (d.signum() == 0) {
          return false;
        }
      } catch (NumberFormatException e) {
        return null;
      }
      return null;
    }
    if (value instanceof String s) {
      String token = s.strip().toLowerCase(Locale.ROOT);
      if (TRUE_TOKENS.contains(token)) {
        return true;
      }
      if (FALSE_TOKENS.contains(token)) {
        return false;
      }
    }
    return null;
  }

  /**
   * 정수 엄격 변환(64비트). 소수부·범위 밖·불리언·객체는 {@link Failed}. 정확성 판정은 데이터셋 행 API 와 같은 {@link
   * ColumnPhysicalType#toExactBigint}.
   */
  public static long toLongStrict(Object value) {
    if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) {
      return ((Number) value).longValue();
    }
    try {
      BigDecimal d =
          switch (value) {
            case BigInteger bi -> new BigDecimal(bi);
            case BigDecimal bd -> bd;
            case Double x when Double.isFinite(x) -> BigDecimal.valueOf(x);
            case Float x when Float.isFinite(x) -> BigDecimal.valueOf(x.doubleValue());
            case String s when DECIMAL_LITERAL.matcher(s.strip()).matches() ->
                new BigDecimal(s.strip().replace("_", ""));
            default -> throw new Failed(); // Boolean·Map·List·비유한수·숫자 아닌 문자열
          };
      return ColumnPhysicalType.toExactBigint(d, value);
    } catch (IllegalArgumentException | ArithmeticException e) {
      // toExactBigint 의 형식·범위 오류(IllegalArgumentException), 지수가 int 를 넘는 문자열(NumberFormatException)
      throw new Failed();
    }
  }

  /** 문구에 넣을 값 — {@link #VALUE_EXCERPT_MAX} 코드 포인트를 넘으면 잘라 「…」을 붙인다. */
  public static String excerpt(String text) {
    if (text.codePointCount(0, text.length()) <= VALUE_EXCERPT_MAX) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, VALUE_EXCERPT_MAX)) + "…";
  }

  /**
   * 「… 을(를) <b>정수(INTEGER)로</b> 변환할 수 없습니다」의 대상 부분(조사 포함). 별칭은 정식 타입 이름으로 보인다. 라벨은 데이터 임포트
   * 문구(정수·소수·참/거짓·날짜·날짜시간)와 같다.
   */
  public static String target(String dtype) {
    return switch (dtype.toUpperCase(Locale.ROOT)) {
      case "INTEGER" -> "정수(INTEGER)로";
      case "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE" -> "소수(DECIMAL)로";
      case "BOOLEAN" -> "참/거짓(BOOLEAN)으로";
      case "DATE" -> "날짜(DATE)로";
      case "TIMESTAMP", "DATETIME" -> "날짜시간(TIMESTAMP)으로";
      default -> dtype.toUpperCase(Locale.ROOT) + " 타입으로";
    };
  }

  /** 타입별 덧붙임 안내 — 불리언만 허용 값을 알려 준다. */
  public static String hint(String dtype) {
    return "BOOLEAN".equalsIgnoreCase(dtype) ? "(허용 값: true/false/1/0/yes/no)" : "";
  }

  // ---------------------------------------------------------------------------
  // Python str() 재현 — 변환은 str(value) 를 거치므로 JSON 값의 Python 표기가 결과를 정한다
  // ---------------------------------------------------------------------------

  /**
   * JSON 에서 온 값의 Python {@code str()} 표기. bool 은 {@code True/False}, 실수는 Python {@code repr}(최단
   * 표기·지수 규칙), 중첩 객체·배열은 Python {@code repr}({@code {'a': 1}}).
   */
  public static String pyStr(Object value) {
    if (value instanceof String s) {
      return s;
    }
    return pyRepr(value);
  }

  private static String pyRepr(Object value) {
    if (value == null) {
      return "None";
    }
    if (value instanceof String s) {
      return pyStringRepr(s);
    }
    if (value instanceof Boolean b) {
      return b ? "True" : "False";
    }
    if (value instanceof Double d) {
      return pyFloatRepr(d);
    }
    if (value instanceof Float f) {
      return pyFloatRepr(f.doubleValue());
    }
    if (value instanceof BigDecimal bd) {
      return pyFloatRepr(bd.doubleValue());
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .map(e -> pyRepr(e.getKey()) + ": " + pyRepr(e.getValue()))
          .collect(Collectors.joining(", ", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ValueConversion::pyRepr).collect(Collectors.joining(", ", "[", "]"));
    }
    // Integer·Long·BigInteger
    return value.toString();
  }

  /** Python 문자열 repr — 작은따옴표 우선, 작은따옴표만 들어 있으면 큰따옴표. 흔한 이스케이프만 재현한다. */
  private static String pyStringRepr(String s) {
    char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
    StringBuilder out = new StringBuilder().append(quote);
    for (char c : s.toCharArray()) {
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c == quote) {
            out.append('\\').append(c);
          } else if (c < 0x20 || c == 0x7f) {
            out.append(String.format("\\x%02x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append(quote).toString();
  }

  /**
   * Python float repr: 최단 왕복 자릿수(Java 19+ {@link Double#toString} 과 같은 자릿수), 십진 지수가 -4 이상 16 미만이면
   * 고정 소수점(최소 ".0"), 아니면 {@code 1.5e+16}·{@code 1e-05} 꼴.
   */
  public static String pyFloatRepr(double d) {
    if (Double.isNaN(d)) {
      return "nan";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "inf" : "-inf";
    }
    if (d == 0) {
      return (1 / d < 0) ? "-0.0" : "0.0";
    }
    BigDecimal bd = new BigDecimal(Double.toString(d)).stripTrailingZeros();
    String sign = bd.signum() < 0 ? "-" : "";
    String digits = bd.unscaledValue().abs().toString();
    int exp10 = digits.length() - 1 - bd.scale();
    if (exp10 >= -4 && exp10 < 16) {
      String plain = bd.abs().toPlainString();
      return sign + (plain.contains(".") ? plain : plain + ".0");
    }
    String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
    String exp = String.format("%02d", Math.abs(exp10));
    return sign + mantissa + "e" + (exp10 < 0 ? "-" : "+") + exp;
  }
}

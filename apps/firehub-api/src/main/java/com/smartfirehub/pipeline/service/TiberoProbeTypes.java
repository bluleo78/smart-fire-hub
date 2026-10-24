package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeColumn;
import java.util.Locale;

/**
 * Tibero 컬럼 탐지(db-executor probe) 결과를 앱 데이터셋 타입 어휘로 되돌린다(WD-77 Task 7, 스펙 §4.5).
 *
 * <p>PG 컬럼 탐지({@link SqlColumnProbe})와 같은 어휘를 쓴다 — 문자는 길이와 무관하게 TEXT(앱에 VARCHAR(n) 은 없다), 정수는
 * INTEGER(앱 타입에 BIGINT 는 없다). 같은 SQL 이면 항상 같은 결과가 나와야 {@link TempDatasetService#hasSchemaChanged} 가
 * 임시 데이터셋을 다시 만들지 않는다.
 *
 * <p><b>"맨 NUMBER" 판정은 실서버 관측에 맞춘다</b>(Tibero 7, 2026-10-24, db-executor {@code
 * TiberoLiveSqlTest#live_probe_numericExpressionPrecisionAndScale}). 자릿수 없는 NUMBER 와 식 결과는 정수인지 알 수
 * 없으므로(정수로 짐작하면 소수가 잘린다) DECIMAL 로 둔다:
 *
 * <ul>
 *   <li>{@code COUNT(*)}·{@code SUM(정수열)} → precision 0 / scale 0
 *   <li>{@code ROUND(x,2)}·숫자 리터럴 → precision 38 / scale <b>0</b> (소수가 있어도 scale 0 으로 보고된다)
 *   <li>선언된 열 {@code NUMBER(19)} → 19/0, {@code NUMBER(18,6)} → 18/6
 * </ul>
 *
 * 그래서 precision 이 없거나 0 이거나 최대(38) 이상이면 맨 NUMBER 다. 알려진 차이: {@code COUNT(*)} 는 PG 에서 INTEGER 지만
 * 여기서는 DECIMAL 이다.
 */
final class TiberoProbeTypes {

  /** Tibero NUMBER 최대 정밀도 — 식 결과가 이 값(scale 0)으로 보고되면 선언된 정수 열이 아니라 식이다. */
  private static final int MAX_NUMBER_PRECISION = 38;

  private TiberoProbeTypes() {}

  /**
   * probe 컬럼 하나를 앱 데이터셋 타입(TEXT·INTEGER·DECIMAL·BOOLEAN·DATE·TIMESTAMP)으로 바꾼다.
   *
   * @return 앱 타입 — 어디에도 해당하지 않으면 TEXT
   */
  static String toAppType(ProbeColumn c) {
    String type = c.typeName() == null ? "" : c.typeName().toUpperCase(Locale.ROOT);
    // TIMESTAMP 는 DATE 보다 먼저 본다 — "TIMESTAMP WITH TIME ZONE" 같은 이름이 모두 TIMESTAMP 로 시작한다.
    if (type.startsWith("TIMESTAMP")) {
      return "TIMESTAMP";
    }
    if (type.equals("DATE")) {
      return "DATE";
    }
    if (type.contains("CHAR") || type.contains("CLOB") || type.contains("TEXT")) {
      return "TEXT";
    }
    if (type.equals("NUMBER") || type.equals("NUMERIC") || type.equals("DECIMAL")) {
      return numberType(c.precision(), c.scale());
    }
    if (type.contains("FLOAT") || type.contains("DOUBLE") || type.equals("REAL")) {
      return "DECIMAL";
    }
    return "TEXT";
  }

  /** NUMBER 의 자릿수 정보로 BOOLEAN·INTEGER·DECIMAL 을 가른다. */
  private static String numberType(Integer precision, Integer scale) {
    int s = scale == null ? 0 : scale;
    if (s > 0 || s < 0) {
      // 소수 자릿수가 있거나(또는 FLOAT 처럼 음수로 보고) — 정수가 아니다.
      return "DECIMAL";
    }
    if (precision == null || precision <= 0 || precision >= MAX_NUMBER_PRECISION) {
      // 맨 NUMBER·식 결과 — 정수라고 짐작하지 않는다(값 손실 방지).
      return "DECIMAL";
    }
    return precision == 1 ? "BOOLEAN" : "INTEGER";
  }
}

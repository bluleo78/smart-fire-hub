package com.smartfirehub.global.util;

import org.jooq.JSON;
import org.jooq.JSONB;
import org.jooq.XML;
import org.jooq.types.DayToSecond;
import org.jooq.types.Interval;
import org.jooq.types.YearToMonth;
import org.jooq.types.YearToSecond;
import org.postgresql.util.PGobject;

/**
 * 애드혹 SQL 결과 값을 <b>Jackson 이 직렬화할 수 있는 형태</b>로 바꾸는 헬퍼(#756).
 *
 * <p><b>왜 필요한가.</b> 애드혹 결과는 {@code dsl.fetch(ResultSet)} 로 읽어 jOOQ 가 PG 타입별 Java 값을 고른다. 그중
 * 일부는 응답 JSON 으로 그대로 쓸 수 없다:
 *
 * <ul>
 *   <li>{@code json}/{@code jsonb}/{@code xml} → {@link JSON}/{@link JSONB}/{@link XML}. 게터가 없어 Jackson 이
 *       "No serializer found" 로 실패하고 응답이 500 이 됐다.
 *   <li>{@code interval} → {@link YearToSecond}. {@link Number} 를 상속해 Jackson 이 {@code toString()}
 *       ({@code +0-0 +1 02:00:00.000000000})을 <b>따옴표 없는 숫자 토큰</b>으로 써서, 200 인데 본문이 깨진 JSON 이
 *       됐다(클라이언트 JSON 파싱 실패).
 *   <li>PostGIS 외 확장 타입의 배열(예: {@code point[]}, {@code inet[]}) → pgjdbc {@code PgArray} 원본. 빈 빈(bean)
 *       으로 보여 직렬화 실패(500).
 * </ul>
 *
 * <p><b>응답 형태 = 텍스트.</b> 위 값은 PostgreSQL 이 내보내는 텍스트 표현(문자열)으로 준다. geometry 를
 * {@code ST_AsGeoJSON} <b>텍스트</b>로 주는 기존 관례와 같고, 데이터셋 SQL 탭 표({@code String(value)})·애드혹 분석 표
 * 모두 문자열을 그대로 그린다 — 파싱된 객체를 주면 SQL 탭이 {@code [object Object]} 로 그린다. 배열은 원소 단위로
 * 바꾸므로 {@code jsonb[]} 는 JSON 텍스트 원소의 배열이 된다. 단일 PGobject(range·inet·point 등)도 PG 텍스트로
 * 바꾼다(#776, geometry/geography 제외). 그 밖의 값(숫자·문자열·날짜·UUID·bytea 등)은 이미 직렬화되므로 손대지 않는다.
 *
 * <p><b>날짜·시각은 여기 오기 전에 걸러진다.</b> 범위 밖(10000년 이후·BC·infinity·24:00)이거나 Java 날짜 객체가 PG
 * 값을 재현하지 못하는 date/timestamp/timestamptz/time/timetz 와 interval 은 {@link AdhocTemporalValues} 가 읽는
 * 시점에 PG 텍스트 원문으로 바꿔 둔다(#768). 여기 도달하는 날짜 객체는 정상 범위 값이라 Jackson 이 그대로 쓴다.
 */
public final class AdhocResultValues {

  private AdhocResultValues() {}

  /** 결과 셀 값 하나를 응답에 담을 수 있는 값으로 바꾼다. 바꿀 필요가 없으면 그대로 돌려준다. */
  public static Object toResponseValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof JSONB jsonb) {
      return jsonb.data();
    }
    if (value instanceof JSON json) {
      return json.data();
    }
    if (value instanceof XML xml) {
      return xml.data();
    }
    if (value instanceof Interval interval) {
      return formatInterval(interval);
    }
    if (value instanceof PGobject pg) {
      // range·multirange·inet·cidr·macaddr·기하 타입 등 jOOQ 가 모르는 PG 타입(#776). 그대로 두면 Jackson 이 pgjdbc
      // 내부 프로퍼티({type,value,null}, point 는 x·y·isNull 까지)를 객체로 써서 SQL 탭이 [object Object] 로 그린다.
      // executor 경로(#763)와 같이 PG 텍스트 원문(getValue)으로 준다 — 기하 타입은 AdhocGeometricValues 가 읽을 때
      // 원문 텍스트를 담아 둔다. 단 PostGIS geometry/geography 는 제외(현행 유지): 애드혹 분석은 이 PGobject 를 보고
      // GeoJSON 으로 다시 감싸고(#741, detectGeometryColumns 와 같은 판정 규칙), 데이터셋 SQL 탭 형태는 #767 결정 대기다.
      return isSpatial(pg.getType()) ? value : pg.getValue();
    }
    if (value instanceof java.sql.Array sqlArray) {
      // jOOQ 가 원소 타입을 몰라 pgjdbc 배열을 그대로 둔 경우(point[]·inet[] 등). toString() 은 PG 배열 리터럴
      // 텍스트({"(1,2)"})를 커넥션 없이 돌려준다 — 결과를 다 읽은 뒤라 getArray() 로 다시 파싱하지 않는다.
      return sqlArray.toString();
    }
    if (value instanceof Object[] array) {
      // 배열은 원소 단위로 바꾼다(jsonb[] → JSON 텍스트 배열). null 원소는 그대로 null.
      Object[] converted = new Object[array.length];
      for (int i = 0; i < array.length; i++) {
        converted[i] = toResponseValue(array[i]);
      }
      return converted;
    }
    if (value instanceof java.util.List<?> list) {
      // 다차원 배열을 펼친 중첩 리스트(#757, AdhocMultiDimArrays) — 1차원 배열과 같은 원소 단위 변환을 적용한다.
      java.util.List<Object> converted = new java.util.ArrayList<>(list.size());
      for (Object element : list) {
        converted.add(toResponseValue(element));
      }
      return converted;
    }
    return value;
  }

  /**
   * PostGIS 공간 타입 PGobject 인가 — 타입 이름(스키마 한정 {@code "public"."geometry"} 포함)에 geometry/geography 가
   * 들어 있으면 그렇다고 본다. 애드혹 분석의 {@code detectGeometryColumns} 와 같은 규칙이다.
   */
  private static boolean isSpatial(String type) {
    if (type == null) {
      return false;
    }
    String lower = type.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("geometry") || lower.contains("geography");
  }

  /**
   * jOOQ interval 값을 PostgreSQL 기본 출력({@code IntervalStyle = postgres})과 같은 텍스트로 만든다 — 예:
   * {@code 1 year 2 mons 3 days 04:05:06.5}, {@code -1 days}, {@code 00:00:00}. PG 의 {@code EncodeInterval} 규칙을
   * 따른다(0 인 연·월·일 생략, 1 이 아니면 복수형, 앞 필드가 음수면 뒤 양수 필드에 {@code +}, 소수 초 끝 0 제거).
   *
   * <p><b>한계.</b> jOOQ 가 pgjdbc 값을 읽으며 일·시간 부분을 하나의 부호로 정규화하므로 {@code '1 day -1 hour'}
   * 처럼 일과 시간의 부호가 섞인 값은 PG 원문과 다른 (같은 길이의) 표기로 나올 수 있다.
   */
  static String formatInterval(Interval interval) {
    YearToMonth ym;
    DayToSecond ds;
    if (interval instanceof YearToSecond yts) {
      ym = yts.getYearToMonth();
      ds = yts.getDayToSecond();
    } else if (interval instanceof YearToMonth y) {
      ym = y;
      ds = new DayToSecond();
    } else if (interval instanceof DayToSecond d) {
      ym = new YearToMonth();
      ds = d;
    } else {
      return interval.toString();
    }
    int ymSign = ym.getSign();
    int dsSign = ds.getSign();
    long years = (long) ymSign * ym.getYears();
    long months = (long) ymSign * ym.getMonths();
    long days = (long) dsSign * ds.getDays();
    long hours = ds.getHours();
    long minutes = ds.getMinutes();
    long seconds = ds.getSeconds();
    long nanos = ds.getNano();

    StringBuilder sb = new StringBuilder();
    // [0]=is_zero(아직 아무 필드도 안 씀), [1]=is_before(직전 필드가 음수)
    boolean[] state = {true, false};
    appendPart(sb, years, "year", state);
    appendPart(sb, months, "mon", state);
    appendPart(sb, days, "day", state);
    boolean hasTime = hours != 0 || minutes != 0 || seconds != 0 || nanos != 0;
    if (state[0] || hasTime) {
      boolean minus = dsSign < 0 && hasTime;
      if (!state[0]) {
        sb.append(' ');
      }
      sb.append(minus ? "-" : (state[1] ? "+" : ""));
      sb.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
      if (nanos != 0) {
        // PG 는 마이크로초 정밀도 — 끝의 0 을 걷어 낸다(.500000 → .5).
        String frac = String.format("%09d", nanos).substring(0, 6).replaceAll("0+$", "");
        if (!frac.isEmpty()) {
          sb.append('.').append(frac);
        }
      }
    }
    return sb.toString();
  }

  /** PG {@code AddPostgresIntPart} 와 같은 규칙으로 "N unit(s)" 를 덧붙인다. 0 이면 생략. */
  private static void appendPart(StringBuilder sb, long value, String unit, boolean[] state) {
    if (value == 0) {
      return;
    }
    if (!state[0]) {
      sb.append(' ');
    }
    if (state[1] && value > 0) {
      sb.append('+');
    }
    sb.append(value).append(' ').append(unit);
    if (value != 1) {
      sb.append('s');
    }
    state[1] = value < 0;
    state[0] = false;
  }
}

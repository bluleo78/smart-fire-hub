package com.smartfirehub.global.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.Convert;
import org.jooq.types.DayToSecond;
import org.jooq.types.YearToMonth;
import org.jooq.types.YearToSecond;
import org.jooq.util.postgres.PostgresUtils;
import org.postgresql.util.PGInterval;

/**
 * 애드혹 SQL 결과의 <b>다차원 배열</b>({@code ARRAY[[1,2],[3,4]]})을 중첩 리스트로 살려 내는 헬퍼(#757).
 *
 * <p><b>왜 필요한가.</b> jOOQ 는 PG 배열 컬럼을 메타데이터만 보고 1차원 Java 배열({@code Integer[]} 등)로 읽는다 —
 * PG 결과 메타데이터에는 차원 수가 없다. 값이 2차원 이상이면 pgjdbc 가 돌려준 {@code Integer[][]} 를 {@code Integer[]}
 * 로 바꾸지 못해 jOOQ 가 "Cannot parse array" 를 로그로만 남기고 <b>값을 null 로 바꿨다</b>(int·numeric·bool — 조용한
 * 데이터 손실). text 는 안쪽 배열이 PG 리터럴 문자열({@code {"a","b"}})로 뭉개졌다.
 *
 * <p><b>방법.</b> jOOQ 에 넘기는 ResultSet 을 감싸 {@code getArray(int)} 를 가로챈다. 값이 다차원이면 연결이 열려 있는
 * 지금 pgjdbc 로 Java 다차원 배열을 materialize 해 두고 jOOQ 에는 null 을 준다(셀마다 오류 로그·뭉갬을 피한다). 결과를
 * 다 읽은 뒤 그 셀만 중첩 {@link List} 로 채운다. 1차원 배열·배열 아닌 값은 jOOQ 가 읽은 그대로라 기존 응답이 바이트
 * 단위로 같다 — 다차원 값이 하나도 없으면 결과 객체도 그대로 돌려준다.
 *
 * <p><b>응답 형태 = executor 경로와 같은 중첩 리스트.</b> executor(psycopg2)는 다차원 배열을 중첩 리스트로 주고 하한
 * ({@code [0:1]=})은 버린다. 여기서도 같다. 원소는 jOOQ 가 1차원 배열 원소에 쓰는 것과 같은 변환
 * (가장 안쪽 1차원 조각에 {@link Convert#convert(Object, Class)} → 컬럼의 1차원 배열 타입)을 거친 뒤 {@link AdhocResultValues} 가 원소 단위로
 * 다듬는다 — 그래서 2차원 원소의 JSON 형태가 같은 직접 경로의 1차원 원소 형태와 같다(numeric 은 숫자, jsonb 는 텍스트).
 *
 * <p><b>손실 금지.</b> 원소 변환이 하나라도 실패하면 그 셀은 null 이 아니라 PG 배열 리터럴 텍스트
 * ({@code {{1,2},{3,4}}})로 준다.
 */
final class AdhocMultiDimArrays {

  /** 가로챈 다차원 셀 하나 — pgjdbc 가 materialize 한 Java 배열과, 폴백용 PG 리터럴 텍스트. */
  private record Captured(Object javaArray, String literal) {}

  private final ResultSet delegate;

  /** 현재 행 번호(0-based). {@code next()} 가 true 를 돌려줄 때마다 1 증가한다. */
  private int row = -1;

  /** (행 → (1-based 컬럼 인덱스 → 가로챈 값)). */
  private final Map<Integer, Map<Integer, Captured>> captured = new HashMap<>();

  AdhocMultiDimArrays(ResultSet delegate) {
    this.delegate = delegate;
  }

  /** jOOQ 에 넘길 감싼 ResultSet. {@code next()}·{@code getArray(int)} 외에는 원본에 그대로 위임한다. */
  ResultSet proxy() {
    InvocationHandler handler =
        (Object p, Method m, Object[] args) -> {
          if (m.getName().equals("getArray")
              && args != null
              && args.length == 1
              && args[0] instanceof Integer idx) {
            return interceptGetArray(idx);
          }
          Object out;
          try {
            out = m.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
          if (m.getName().equals("next")
              && (args == null || args.length == 0)
              && Boolean.TRUE.equals(out)) {
            row++;
          }
          return out;
        };
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
  }

  /**
   * 다차원 배열이면 materialize 해 기록하고 null 을 준다. 판정 중 무엇이 실패하든 원본 배열을 그대로 넘겨 jOOQ 의 기존
   * 동작을 유지한다(이 헬퍼가 새 실패 경로를 만들지 않도록).
   */
  private java.sql.Array interceptGetArray(int idx) throws java.sql.SQLException {
    java.sql.Array array = delegate.getArray(idx);
    if (array == null) {
      return null;
    }
    try {
      Object javaArray = array.getArray();
      if (javaArray instanceof Object[] outer && outer.getClass().getComponentType().isArray()) {
        captured
            .computeIfAbsent(row, r -> new HashMap<>())
            .put(idx, new Captured(javaArray, array.toString()));
        return null;
      }
    } catch (Exception ignore) {
      // 판정 실패 — jOOQ 에 원본을 넘겨 예전과 같게 처리한다.
    }
    return array;
  }

  /**
   * 가로챈 셀을 결과에 채운다. 가로챈 것이 없으면 결과를 그대로 돌려준다. 있으면 해당 컬럼만 {@code OTHER}(Object)
   * 타입으로 바꾼 새 결과를 만든다 — 원래 타입({@code Integer[]})에는 중첩 리스트를 담을 수 없다. 호출자는 컬럼
   * 이름과 값만 쓰므로 타입 변경은 응답에 드러나지 않는다.
   */
  Result<Record> apply(DSLContext dsl, Result<Record> result) {
    if (captured.isEmpty()) {
      return result;
    }
    Field<?>[] fields = result.fields();
    // 가장 안쪽 1차원 조각의 변환 대상 = jOOQ 가 이 컬럼에 고른 1차원 배열 타입(예: Integer[], YearToSecond[])
    Map<Integer, Class<?>> sliceTypes = new HashMap<>();
    Field<?>[] newFields = fields.clone();
    for (Map<Integer, Captured> cols : captured.values()) {
      for (int idx : cols.keySet()) {
        int i = idx - 1;
        if (!sliceTypes.containsKey(idx)) {
          Class<?> type = fields[i].getType();
          sliceTypes.put(idx, type.isArray() ? type : Object.class);
          newFields[i] = DSL.field(DSL.name(fields[i].getName()), SQLDataType.OTHER);
        }
      }
    }
    Result<Record> out = dsl.newResult(newFields);
    for (int r = 0; r < result.size(); r++) {
      Object[] values = result.get(r).intoArray();
      Map<Integer, Captured> cols = captured.get(r);
      if (cols != null) {
        for (Map.Entry<Integer, Captured> e : cols.entrySet()) {
          values[e.getKey() - 1] = toNested(e.getValue().javaArray(), e.getValue().literal(), sliceTypes.get(e.getKey()));
        }
      }
      Record rec = dsl.newRecord(newFields);
      for (int i = 0; i < newFields.length; i++) {
        @SuppressWarnings("unchecked")
        Field<Object> f = (Field<Object>) newFields[i];
        rec.set(f, values[i]);
      }
      rec.changed(false);
      out.add(rec);
    }
    return out;
  }

  /** 다차원 Java 배열을 중첩 리스트로 바꾼다. 하나라도 실패하면 셀 전체를 PG 리터럴 텍스트로 준다(손실 금지). */
  static Object toNested(Object javaArray, String literal, Class<?> sliceType) {
    try {
      return nest(javaArray, sliceType);
    } catch (Exception e) {
      return literal;
    }
  }

  /**
   * 바깥 차원은 리스트로 펼치고, 가장 안쪽 1차원 조각은 jOOQ 가 1차원 배열을 읽을 때와 <b>같은 호출</b>
   * ({@code Convert.convert(array.getArray(), Integer[].class)} — DefaultBinding.convertArray)로 바꾼다. 그래서
   * 원소 형태(interval → YearToSecond, date → LocalDate, jsonb → JSONB …)가 1차원 배열 원소와 정확히 같다.
   */
  private static Object nest(Object value, Class<?> sliceType) {
    if (!(value instanceof Object[] array)) {
      return value; // 빈 하위 배열 등 — 조각이 아닌 값은 그대로
    }
    if (array.getClass().getComponentType().isArray()) {
      List<Object> list = new ArrayList<>(array.length);
      for (Object element : array) {
        list.add(nest(element, sliceType));
      }
      return list;
    }
    if (sliceType == Object.class) {
      // jOOQ 가 원소 타입을 모르는 경우 — PGobject 는 PG 텍스트 표현으로 준다(빈(bean) 덤프 방지)
      List<Object> list = new ArrayList<>(array.length);
      for (Object element : array) {
        list.add(element instanceof org.postgresql.util.PGobject pg ? pg.getValue() : element);
      }
      return list;
    }
    Object[] converted = (Object[]) Convert.convert(intervalsToJooq(array, sliceType), sliceType);
    if (converted == null || converted.length != array.length) {
      throw new IllegalStateException("배열 조각 변환 실패: " + array.getClass().getName());
    }
    for (int i = 0; i < array.length; i++) {
      // jOOQ Convert 는 변환 불가 원소를 null 로 줄 수 있다 — 조용한 손실 대신 셀 폴백으로 보낸다.
      if (array[i] != null && converted[i] == null) {
        throw new IllegalStateException("원소 변환 실패: " + array[i].getClass().getName());
      }
    }
    return new ArrayList<>(java.util.Arrays.asList(converted));
  }

  /**
   * interval 원소는 Convert 가 PGInterval 을 바꾸지 못해 jOOQ 도 1차원 배열에서 원소 바인딩(PostgresUtils)으로 따로
   * 읽는다. 같은 변환을 여기서 먼저 적용해 1차원 interval 배열 원소와 같은 값을 만든다.
   */
  private static Object[] intervalsToJooq(Object[] array, Class<?> sliceType) {
    Class<?> component = sliceType.getComponentType();
    if (component != YearToSecond.class
        && component != DayToSecond.class
        && component != YearToMonth.class) {
      return array;
    }
    Object[] out = new Object[array.length];
    for (int i = 0; i < array.length; i++) {
      Object v = array[i];
      if (v instanceof PGInterval pg) {
        v =
            component == YearToSecond.class
                ? PostgresUtils.toYearToSecond(pg)
                : component == DayToSecond.class
                    ? PostgresUtils.toDayToSecond(pg)
                    : PostgresUtils.toYearToMonth(pg);
      }
      out[i] = v;
    }
    return out;
  }
}

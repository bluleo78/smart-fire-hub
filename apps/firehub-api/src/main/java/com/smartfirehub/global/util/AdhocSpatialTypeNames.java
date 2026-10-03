package com.smartfirehub.global.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Locale;
import java.util.Set;

/**
 * 애드혹 SQL 결과의 PostGIS 타입 이름을 커넥션 상태와 무관한 한 가지 표기로 고정하는 ResultSet 래퍼(#759).
 *
 * <p><b>왜 필요한가.</b> pgjdbc 는 커넥션마다 "타입 OID → 타입 이름" 캐시를 두고, 이름을 <b>그 OID 를 처음 조회한 순간의
 * search_path</b> 로 정한다 — public 이 경로에 있으면 {@code geometry}, 없으면 {@code "public"."geometry"}. 이후엔
 * search_path 가 바뀌어도 캐시된 이름이 그대로 나온다(실측). 애드혹 분석 경로는 search_path 에 public 을 넣고 데이터셋 SQL 탭은 데이터 스키마만
 * 두므로, 같은 풀 커넥션에서 무엇이 먼저 geometry 를 읽었느냐에 따라 메타데이터 이름이 달라진다.
 *
 * <p>jOOQ 는 한정 없는 {@code geometry}/{@code geography} 를 자기 공간 타입으로 해석하는데, 그 바인딩은 상용판 전용이라 OSS 에서
 * "Error while reading field" 로 실패한다. 한정된 {@code "public"."geometry"} 는 모르는 타입이라 pgjdbc 의 PGobject
 * 로 읽는다. 그래서 분석이 먼저 geometry 를 읽은 커넥션에서는 데이터셋 탭의 geometry 조회가 커넥션 수명 내내 실패했다.
 *
 * <p><b>방법.</b> 메타데이터의 타입 이름이 한정 없는 PostGIS 타입이면 public 으로 한정한 이름으로 바꿔 준다 — search_path 에 public 이
 * 없던 커넥션에서 pgjdbc 가 원래 돌려주던 표기와 같다(PostGIS 는 public 스키마에 설치돼 있다). 이로써 두 경로가 순서와 무관하게 늘 PGobject 로
 * 읽고, 분석 경로는 그 PGobject 를 보고 GeoJSON 래핑을 한다. 그 밖의 메서드는 원본에 그대로 위임한다.
 */
final class AdhocSpatialTypeNames {

  /** jOOQ 가 상용판 전용 바인딩으로 해석하는 한정 없는 PostGIS 타입 이름(배열 포함, 소문자). */
  private static final Set<String> SPATIAL =
      Set.of("geometry", "geography", "_geometry", "_geography");

  private AdhocSpatialTypeNames() {}

  /** {@code getMetaData()} 만 가로채는 ResultSet 래퍼를 돌려준다. */
  static ResultSet wrap(ResultSet delegate) {
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (p, m, args) -> {
              Object out = invoke(m, delegate, args);
              if (m.getName().equals("getMetaData") && out instanceof ResultSetMetaData meta) {
                return wrapMeta(meta);
              }
              return out;
            });
  }

  /** {@code getColumnTypeName(int)} 만 고쳐 주는 메타데이터 래퍼. */
  private static ResultSetMetaData wrapMeta(ResultSetMetaData delegate) {
    return (ResultSetMetaData)
        Proxy.newProxyInstance(
            ResultSetMetaData.class.getClassLoader(),
            new Class<?>[] {ResultSetMetaData.class},
            (p, m, args) -> {
              Object out = invoke(m, delegate, args);
              if (m.getName().equals("getColumnTypeName") && out instanceof String name) {
                return normalize(name);
              }
              return out;
            });
  }

  /** 한정 없는 PostGIS 타입 이름이면 {@code "public"."<name>"} 로, 아니면 그대로 돌려준다. */
  static String normalize(String typeName) {
    String lower = typeName.toLowerCase(Locale.ROOT);
    if (SPATIAL.contains(lower)) {
      return "\"public\".\"" + lower + "\"";
    }
    return typeName;
  }

  /** 리플렉션 호출 — 원본이 던진 예외를 감싸지 않고 그대로 전달한다. */
  private static Object invoke(java.lang.reflect.Method m, Object target, Object[] args)
      throws Throwable {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}

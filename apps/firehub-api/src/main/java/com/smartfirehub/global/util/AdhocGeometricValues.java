package com.smartfirehub.global.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import org.postgresql.util.PGobject;

/**
 * 애드혹 SQL 결과의 PG 기하 타입(point·box·circle·lseg·line·path·polygon) 값을 <b>서버가 보낸 텍스트 원문</b>을 담은 일반
 * {@link PGobject} 로 바꿔 주는 ResultSet 래퍼(#776).
 *
 * <p><b>왜 필요한가.</b> pgjdbc 는 기하 타입을 {@code org.postgresql.geometric} 의 하위 클래스(PGpoint 등)로 읽고, 그
 * {@code getValue()} 는 원문이 아니라 double 로 다시 포맷한 텍스트다 — PG·executor 경로는 {@code (1,2)} 인데 {@code
 * (1.0,2.0)} 이 된다. 결과를 다 읽은 뒤에는 원문을 되찾을 수 없으므로, 읽는 시점에 {@code getString} (서버 텍스트) 으로 바꿔 둔다. 응답 변환은
 * {@link AdhocResultValues#toResponseValue} 가 일반 PGobject 를 원문 텍스트로 내보내며 한다.
 *
 * <p>{@code getObject(int)} 만 가로채고, 결과가 {@code org.postgresql.geometric} 패키지 객체일 때만 바꾼다 — 배열
 * ({@code PgArray})·interval({@code PGInterval})·PostGIS(일반 PGobject) 등은 손대지 않는다.
 */
final class AdhocGeometricValues {

  private static final String GEOMETRIC_PACKAGE = "org.postgresql.geometric";

  private AdhocGeometricValues() {}

  /** {@code getObject(int)} 의 기하 타입 결과만 원문 텍스트 PGobject 로 바꾸는 ResultSet 래퍼를 돌려준다. */
  static ResultSet wrap(ResultSet delegate) {
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (p, m, args) -> {
              Object out = invoke(m, delegate, args);
              if (m.getName().equals("getObject")
                  && args != null
                  && args.length == 1
                  && args[0] instanceof Integer idx
                  && out instanceof PGobject pg
                  && GEOMETRIC_PACKAGE.equals(out.getClass().getPackageName())) {
                String text = delegate.getString(idx);
                if (text == null) {
                  return null;
                }
                PGobject raw = new PGobject();
                raw.setType(pg.getType());
                raw.setValue(text);
                return raw;
              }
              return out;
            });
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

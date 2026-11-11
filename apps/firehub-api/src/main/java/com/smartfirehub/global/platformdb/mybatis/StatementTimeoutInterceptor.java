package com.smartfirehub.global.platformdb.mybatis;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/**
 * 호출마다 다른 문장 시간 제한(JDBC {@code Statement.setQueryTimeout}, 초)을 매퍼 파라미터로 거는 MyBatis 플러그인(WD-707).
 *
 * <p>왜 필요한가: XML 의 {@code timeout} 속성은 정적이라 호출자가 정하는 Duration(예: 커밋 직전 경로의 행 잠금 대기 상한, WD-664 4b)을
 * 실을 수 없다. jOOQ 의 {@code queryTimeout(n)} 과 같은 의미를 두 DB 에서 내려고, 문장 준비 직후 파라미터 맵에 {@link #PARAM} 키가
 * 있으면 그 값(초)을 문장에 건다. 스레드 로컬이 아니라 파라미터로 전달하므로 누수·교차 오염이 없다. 값이 없거나 0 이하이면 아무것도 하지 않는다(기존 제한을 그대로
 * 둔다).
 */
@Intercepts({
  @Signature(
      type = StatementHandler.class,
      method = "prepare",
      args = {Connection.class, Integer.class})
})
public class StatementTimeoutInterceptor implements Interceptor {

  /** 매퍼 {@code @Param} 이름 — 값은 초 단위 정수(0 이하는 무시된다 — 0 은 JDBC 에서 「제한 없음」이라 기존 제한을 지우지 않게 한다). */
  public static final String PARAM = "queryTimeoutSeconds";

  /** 문장 준비 뒤 파라미터의 {@link #PARAM} 값(초)을 문장 시간 제한으로 건다 — 0 이하는 무시하고, 이미 더 짧은 제한은 늘리지 않는다. */
  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object statement = invocation.proceed();
    StatementHandler handler = (StatementHandler) invocation.getTarget();
    Object parameter = handler.getBoundSql().getParameterObject();
    // ParamMap.get 은 없는 키에 예외를 던지므로 containsKey 로 먼저 확인한다.
    if (parameter instanceof Map<?, ?> map
        && map.containsKey(PARAM)
        && map.get(PARAM) instanceof Number seconds
        && statement instanceof Statement stmt) {
      int requested = seconds.intValue();
      // 0 이하는 무시한다 — setQueryTimeout(0) 은 「제한 없음」이라, 걸어 두면 이미 있던 트랜잭션 시간 제한까지 지워 버린다.
      if (requested > 0) {
        // MyBatis 가 이미 더 짧은 제한(스프링 트랜잭션 남은 시간)을 걸었다면 그것을 늘리지 않는다.
        int existing = stmt.getQueryTimeout();
        stmt.setQueryTimeout(existing > 0 ? Math.min(existing, requested) : requested);
      }
    }
    return statement;
  }
}

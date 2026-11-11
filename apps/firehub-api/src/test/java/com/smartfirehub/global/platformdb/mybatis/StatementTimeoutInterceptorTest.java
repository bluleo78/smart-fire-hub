package com.smartfirehub.global.platformdb.mybatis;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** StatementTimeoutInterceptor 단위 시험 — 파라미터에 값이 있을 때만 문장 시간 제한이 걸린다(WD-707). */
class StatementTimeoutInterceptorTest {

  private Object run(Object parameterObject, PreparedStatement statement) throws Throwable {
    StatementHandler handler = mock(StatementHandler.class);
    BoundSql boundSql = mock(BoundSql.class);
    when(handler.getBoundSql()).thenReturn(boundSql);
    when(boundSql.getParameterObject()).thenReturn(parameterObject);
    Method prepare = StatementHandler.class.getMethod("prepare", Connection.class, Integer.class);
    // 실제 prepare 대신 미리 만든 문장을 돌려주는 대상 — Invocation 은 handler 의 prepare 를 호출한다.
    when(handler.prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(statement);
    Invocation invocation =
        new Invocation(handler, prepare, new Object[] {mock(Connection.class), null});
    return new StatementTimeoutInterceptor().intercept(invocation);
  }

  @Test
  @DisplayName("queryTimeoutSeconds 파라미터가 있으면 setQueryTimeout(초)을 건다")
  void appliesTimeoutWhenParameterPresent() throws Throwable {
    PreparedStatement stmt = mock(PreparedStatement.class);
    ParamMap<Object> params = new ParamMap<>();
    params.put(StatementTimeoutInterceptor.PARAM, 3);

    run(params, stmt);

    verify(stmt).setQueryTimeout(3);
  }

  @Test
  @DisplayName("이미 더 짧은 제한이 걸려 있으면 늘리지 않고, 더 길면 줄인다")
  void keepsShorterExistingTimeout() throws Throwable {
    PreparedStatement shorter = mock(PreparedStatement.class);
    when(shorter.getQueryTimeout()).thenReturn(2);
    ParamMap<Object> params = new ParamMap<>();
    params.put(StatementTimeoutInterceptor.PARAM, 5);
    run(params, shorter);
    verify(shorter).setQueryTimeout(2);

    PreparedStatement longer = mock(PreparedStatement.class);
    when(longer.getQueryTimeout()).thenReturn(30);
    run(params, longer);
    verify(longer).setQueryTimeout(5);
  }

  @Test
  @DisplayName("파라미터가 없거나 맵이 아니면 아무것도 걸지 않는다")
  void doesNothingWithoutParameter() throws Throwable {
    PreparedStatement stmt = mock(PreparedStatement.class);

    run(new ParamMap<>(), stmt);
    run(Map.of("other", 1), stmt);
    run(7L, stmt);

    verify(stmt, never()).setQueryTimeout(anyInt());
  }

  @Test
  @DisplayName("0 이하 요청은 무시한다 — 0(제한 없음)으로 기존 트랜잭션 시간 제한을 지우지 않는다")
  void ignoresNonPositiveRequest() throws Throwable {
    for (int requested : new int[] {0, -1}) {
      PreparedStatement withExisting = mock(PreparedStatement.class);
      when(withExisting.getQueryTimeout()).thenReturn(10);
      ParamMap<Object> params = new ParamMap<>();
      params.put(StatementTimeoutInterceptor.PARAM, requested);

      PreparedStatement withoutExisting = mock(PreparedStatement.class);
      run(params, withExisting);
      run(params, withoutExisting);

      verify(withExisting, never()).setQueryTimeout(anyInt());
      verify(withoutExisting, never()).setQueryTimeout(anyInt());
    }
  }
}

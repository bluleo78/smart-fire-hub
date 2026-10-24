package com.smartfirehub.dbexecutor.sql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.TenantPools;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * SqlRunner·ProbeRunner 단위 테스트용 모의 JDBC. 실행한 SQL·COMMIT·ROLLBACK·CANCEL 을 순서대로 {@link #events} 에
 * 남기고, 문장별 동작은 {@link #handler} 로 바꾼다. 실제 {@link TenantPools} 에 가짜 DataSource 를 끼워 대여(Lease) 규칙도 함께
 * 탄다.
 */
final class FakeJdbc {

  /** 문장 실행 동작. true 를 돌려주면 결과 집합이 있는 문장이다({@link #resultSet} 사용). */
  interface Handler {
    boolean handle(String sql) throws Exception;
  }

  static final StoreDescriptor DESCRIPTOR =
      new StoreDescriptor(
          5L,
          "TIBERO",
          "10.0.0.1",
          8629,
          "tibero",
          "GENIA_T5",
          new StoreDescriptor.Account("GENIA_T5", "pw-secret"),
          new StoreDescriptor.Account("GENIA_T5_RO", "ro-secret"));

  final List<String> events = Collections.synchronizedList(new ArrayList<>());
  final List<Integer> queryTimeouts = Collections.synchronizedList(new ArrayList<>());
  final AtomicInteger factoryCalls = new AtomicInteger();
  final CountDownLatch cancelled = new CountDownLatch(1);
  volatile Handler handler = sql -> false;
  volatile int updateCount = 3;
  volatile ResultSet resultSet;
  volatile SQLException connectFailure;
  final Connection connection = mock(Connection.class);
  final TenantPools pools;

  /** 읽기 전용 계정 풀(같은 가짜 DataSource, 계정만 RO 로 선택). */
  final TenantPools roPools;

  /** 슬롯 계정 풀(같은 가짜 DataSource, 키 (테넌트, 슬롯), WD-78). 만든 슬롯 번호는 {@link #slotFactorySlots} 에 남는다. */
  final TenantPools slotPools;

  final List<Integer> slotFactorySlots = Collections.synchronizedList(new ArrayList<>());

  /** setFetchSize 로 건 값(슬롯 스트리밍). */
  final List<Integer> fetchSizes = Collections.synchronizedList(new ArrayList<>());

  /** 적재(PreparedStatement 배치) 기록: 준비한 SQL 은 events 에, 바인딩은 여기에("setInt(1,7)" 형태). */
  final List<String> binds = Collections.synchronizedList(new ArrayList<>());

  /** executeBatch 직전 훅(호출 번호 1부터). 예외를 던지면 그 배치가 실패한다. */
  interface BatchHook {
    void before(int call) throws Exception;
  }

  volatile BatchHook batchHook = call -> {};

  final List<Integer> maxRows = Collections.synchronizedList(new ArrayList<>());

  FakeJdbc() throws SQLException {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection())
        .thenAnswer(
            inv -> {
              if (connectFailure != null) {
                throw connectFailure;
              }
              return connection;
            });
    when(connection.createStatement()).thenAnswer(inv -> newStatement());
    when(connection.prepareStatement(anyString()))
        .thenAnswer(inv -> newPrepared(inv.getArgument(0)));
    doAnswer(inv -> events.add("COMMIT")).when(connection).commit();
    doAnswer(inv -> events.add("ROLLBACK")).when(connection).rollback();
    pools =
        new TenantPools(
            d -> {
              factoryCalls.incrementAndGet();
              return ds;
            },
            StoreDescriptor::owner,
            8);
    roPools =
        new TenantPools(
            d -> {
              factoryCalls.incrementAndGet();
              return ds;
            },
            StoreDescriptor::ro,
            8);
    slotPools =
        TenantPools.forSlots(
            (d, slot) -> {
              factoryCalls.incrementAndGet();
              slotFactorySlots.add(slot);
              return ds;
            },
            8);
  }

  /** 적재용 PreparedStatement 모의 — 바인딩·addBatch·executeBatch·cancel·setQueryTimeout 을 기록한다. */
  private PreparedStatement newPrepared(String sql) throws SQLException {
    PreparedStatement ps = mock(PreparedStatement.class);
    events.add(sql);
    int[] pending = {0};
    int[] calls = {0};
    doAnswer(inv -> binds.add("setObject(" + inv.getArgument(0) + "," + inv.getArgument(1) + ")"))
        .when(ps)
        .setObject(anyInt(), any());
    doAnswer(inv -> binds.add("setInt(" + inv.getArgument(0) + "," + inv.getArgument(1) + ")"))
        .when(ps)
        .setInt(anyInt(), anyInt());
    doAnswer(inv -> binds.add("setNull(" + inv.getArgument(0) + "," + inv.getArgument(1) + ")"))
        .when(ps)
        .setNull(anyInt(), anyInt());
    doAnswer(inv -> pending[0]++).when(ps).addBatch();
    when(ps.executeBatch())
        .thenAnswer(
            inv -> {
              batchHook.before(++calls[0]);
              events.add("BATCH " + pending[0]);
              int[] result = new int[pending[0]];
              java.util.Arrays.fill(result, 1);
              pending[0] = 0;
              return result;
            });
    doAnswer(
            inv -> {
              events.add("CANCEL");
              cancelled.countDown();
              return null;
            })
        .when(ps)
        .cancel();
    doAnswer(
            inv -> {
              queryTimeouts.add(inv.getArgument(0));
              return null;
            })
        .when(ps)
        .setQueryTimeout(anyInt());
    return ps;
  }

  private Statement newStatement() throws SQLException {
    Statement st = mock(Statement.class);
    when(st.execute(anyString()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              events.add(sql);
              return handler.handle(sql);
            });
    when(st.executeQuery(anyString()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              events.add(sql);
              handler.handle(sql);
              return resultSet;
            });
    doAnswer(
            inv -> {
              maxRows.add(inv.getArgument(0));
              return null;
            })
        .when(st)
        .setMaxRows(anyInt());
    doAnswer(
            inv -> {
              fetchSizes.add(inv.getArgument(0));
              return null;
            })
        .when(st)
        .setFetchSize(anyInt());
    when(st.getUpdateCount()).thenAnswer(inv -> updateCount);
    when(st.getResultSet()).thenAnswer(inv -> resultSet);
    doAnswer(
            inv -> {
              events.add("CANCEL");
              cancelled.countDown();
              return null;
            })
        .when(st)
        .cancel();
    doAnswer(
            inv -> {
              queryTimeouts.add(inv.getArgument(0));
              return null;
            })
        .when(st)
        .setQueryTimeout(anyInt());
    return st;
  }
}

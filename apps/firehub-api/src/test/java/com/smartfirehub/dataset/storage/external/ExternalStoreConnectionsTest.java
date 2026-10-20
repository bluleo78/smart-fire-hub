package com.smartfirehub.dataset.storage.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.global.exception.CodedApiException;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 테넌트 Tibero 커넥션 풀·트랜잭션 묶음 단위 테스트(WD-74). 트랜잭션은 동기화 매니저로 흉내 낸다. */
class ExternalStoreConnectionsTest {

  /** 풀 DataSource 가짜 — close 호출을 관측하기 위해 Closeable 도 구현한다. */
  interface CloseableDataSource extends DataSource, Closeable {}

  private TenantDataStoreRepository repository;
  private EncryptionService encryption;
  private ExternalStorePoolFactory factory;
  private ExternalStoreConnections connections;

  @BeforeEach
  void setUp() {
    repository = mock(TenantDataStoreRepository.class);
    encryption = mock(EncryptionService.class);
    factory = mock(ExternalStorePoolFactory.class);
    connections = new ExternalStoreConnections(repository, encryption, factory, 2);
    when(encryption.decrypt("enc")).thenReturn("plain-pw");
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
    // 수명주기를 끝까지 돈 테스트(expectClean)는 묶인 리소스가 하나도 남지 않아야 한다.
    List<Object> leftover =
        List.copyOf(TransactionSynchronizationManager.getResourceMap().keySet());
    // 방어적 정리: afterCompletion 을 부르지 않고 끝난 테스트가 남긴 리소스가 다음 테스트로 새지 않게 한다.
    for (Object key : leftover) {
      TransactionSynchronizationManager.unbindResourceIfPossible(key);
    }
    if (expectClean) {
      assertThat(leftover).as("트랜잭션 종료 후 남은 묶인 리소스").isEmpty();
    }
  }

  /** true 인 테스트는 트랜잭션 수명주기를 끝까지 돌았으므로 종료 시 묶인 리소스가 비어 있어야 한다. */
  private boolean expectClean;

  /** 실제 트랜잭션 매니저(PG 쪽은 가짜 DataSource) 위의 템플릿. */
  private TransactionTemplate template(int propagation) {
    DataSource pgDs = mock(DataSource.class);
    try {
      when(pgDs.getConnection()).thenAnswer(i -> mock(Connection.class));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(pgDs));
    t.setPropagationBehavior(propagation);
    return t;
  }

  /** READY 2, 3 을 더해 세 번째 테넌트가 들어오면 풀 1 이 (대여 해제됐다면) 축출되는지 확인한다. */
  private void assertPool1Evictable(CloseableDataSource p1) throws Exception {
    ready(2);
    ready(3);
    pool(2, mock(Connection.class));
    pool(3, mock(Connection.class));
    connections.withDml(2, x -> 1);
    connections.withDml(3, x -> 1);
    verify(p1).close();
  }

  /** READY 상태의 테넌트 저장소 행과 OWNER 계정을 준비한다. */
  private void ready(long tenantId) {
    when(repository.find(tenantId))
        .thenReturn(
            Optional.of(
                new TenantDataStoreRow(
                    tenantId,
                    DataStoreBackend.TIBERO,
                    "h",
                    8629,
                    "tb",
                    "SCH" + tenantId,
                    null,
                    DataStoreProvisionMode.DBA_AUTO,
                    TenantDataStoreStatus.READY)));
    when(repository.findAccounts(tenantId))
        .thenReturn(
            List.of(new StoredAccount(DataStoreAccountRole.OWNER, "owner" + tenantId, "enc")));
  }

  /** 팩토리가 돌려줄 가짜 풀. conns 는 getConnection 이 차례로 돌려줄 커넥션이다. */
  private CloseableDataSource pool(long tenantId, Connection... conns) throws SQLException {
    CloseableDataSource ds = mock(CloseableDataSource.class);
    if (conns.length == 1) {
      when(ds.getConnection()).thenReturn(conns[0]);
    } else if (conns.length > 1) {
      when(ds.getConnection()).thenReturn(conns[0], Arrays.copyOfRange(conns, 1, conns.length));
    }
    when(factory.create(eq(tenantId), any(ExternalDbTarget.class), anyString(), eq("plain-pw")))
        .thenReturn(ds);
    return ds;
  }

  /** DDL 직결 커넥션 가짜 — 세션 NLS 초기화 문장을 받을 Statement 를 돌려준다. */
  private Connection directConnection() throws SQLException {
    Connection c = mock(Connection.class);
    when(c.createStatement()).thenReturn(mock(Statement.class));
    return c;
  }

  private void beginTransaction() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  private List<TransactionSynchronization> syncs() {
    return TransactionSynchronizationManager.getSynchronizations();
  }

  @Test
  void withDml_inTransaction_reusesOneConnection_commitsBeforeCommit() throws Exception {
    ready(1);
    Connection c = mock(Connection.class);
    CloseableDataSource ds = pool(1, c);
    beginTransaction();

    connections.withDml(1, x -> 1);
    connections.withDml(1, x -> 2);
    connections.withDml(1, x -> 3);

    verify(ds, times(1)).getConnection();
    verify(c).setAutoCommit(false);
    verify(c, never()).close();

    List<TransactionSynchronization> list = syncs();
    assertThat(list).hasSize(1);
    list.get(0).beforeCommit(false);
    verify(c).commit();
    list.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    InOrder order = inOrder(c);
    order.verify(c).setAutoCommit(true);
    order.verify(c).close();
    verify(c, never()).rollback();
  }

  @Test
  void withDml_inTransaction_rollbackOnRolledBack() throws Exception {
    ready(1);
    Connection c = mock(Connection.class);
    pool(1, c);
    beginTransaction();

    connections.withDml(1, x -> 1);
    syncs().get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    InOrder order = inOrder(c);
    order.verify(c).rollback();
    order.verify(c).close();
  }

  @Test
  void withDml_commitFailure_propagates() throws Exception {
    ready(1);
    Connection c = mock(Connection.class);
    pool(1, c);
    doThrow(new SQLException("commit boom")).when(c).commit();
    beginTransaction();

    connections.withDml(1, x -> 1);
    TransactionSynchronization sync = syncs().get(0);

    assertThatThrownBy(() -> sync.beforeCommit(false)).isInstanceOf(DataAccessException.class);
    // 커밋이 실패했으니 afterCompletion 은 롤백으로 정리해야 한다.
    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(c).rollback();
    verify(c).close();
  }

  @Test
  void withDml_afterCompletion_unbindsSoNextTransactionGetsNewConnection() throws Exception {
    ready(1);
    Connection c1 = mock(Connection.class);
    Connection c2 = mock(Connection.class);
    CloseableDataSource ds = pool(1, c1, c2);
    beginTransaction();
    connections.withDml(1, x -> 1);
    syncs().get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

    connections.withDml(1, x -> 2);

    verify(ds, times(2)).getConnection();
  }

  @Test
  void withDml_noTransaction_autoCommitPerCall() throws Exception {
    ready(1);
    Connection c1 = mock(Connection.class);
    Connection c2 = mock(Connection.class);
    CloseableDataSource ds = pool(1, c1, c2);

    connections.withDml(1, x -> 1);
    connections.withDml(1, x -> 2);

    verify(ds, times(2)).getConnection();
    verify(c1).setAutoCommit(true);
    verify(c1).close();
    verify(c2).close();
  }

  @Test
  void withDml_synchronizationWithoutActualTransaction_usesAutoCommit() throws Exception {
    ready(1);
    Connection c = mock(Connection.class);
    pool(1, c);
    TransactionSynchronizationManager.initSynchronization();

    connections.withDml(1, x -> 1);

    assertThat(syncs()).isEmpty();
    verify(c).close();
  }

  @Test
  void withDdl_directConnection_notFromPool_initsSessionThenClosed() throws Exception {
    ready(1);
    Connection bound = mock(Connection.class);
    CloseableDataSource ds = pool(1, bound);
    beginTransaction();
    connections.withDml(1, x -> 1);
    Connection ddl = mock(Connection.class);
    Statement init = mock(Statement.class);
    when(ddl.createStatement()).thenReturn(init);
    when(factory.openDirect(any(ExternalDbTarget.class), eq("owner1"), eq("plain-pw")))
        .thenReturn(ddl);
    List<String> events = new java.util.ArrayList<>();
    doAnswer(
            i -> {
              events.add("init");
              return false;
            })
        .when(init)
        .execute(TiberoDialect.SESSION_INIT_SQL);

    Object used =
        connections.withDdl(
            1,
            c -> {
              events.add("work");
              return c;
            });

    // 풀에서는 묶인 DML 커넥션 하나만 꺼냈다 — DDL 은 풀을 쓰지 않는다.
    verify(ds, times(1)).getConnection();
    assertThat(used).isSameAs(ddl);
    assertThat(events).containsExactly("init", "work");
    verify(ddl).setAutoCommit(true);
    verify(ddl).close();
    verify(init).close();
    verify(bound, never()).close();
  }

  @Test
  void withDdl_worksWhileEveryPooledConnectionIsLeased() throws Exception {
    ready(1);
    CloseableDataSource ds = pool(1, mock(Connection.class));
    // 첫 커넥션(묶인 DML) 뒤에는 풀이 바닥났다 — 다시 꺼내면 Hikari 처럼 시간 초과로 실패한다.
    when(ds.getConnection())
        .thenReturn(mock(Connection.class))
        .thenThrow(new java.sql.SQLTransientConnectionException("pool exhausted"));
    beginTransaction();
    connections.withDml(1, x -> 1);
    Connection ddl = directConnection();
    when(factory.openDirect(any(ExternalDbTarget.class), anyString(), anyString())).thenReturn(ddl);

    int result = connections.withDdl(1, c -> 7);

    assertThat(result).isEqualTo(7);
    verify(ds, times(1)).getConnection();
    verify(ddl).close();
  }

  @Test
  void withDdl_existingPool_usesCachedCredentials_withoutPgReadOrDecrypt() throws Exception {
    ready(1);
    pool(1, mock(Connection.class));
    connections.withDml(1, x -> 1); // 풀 생성(저장소 행·계정 읽기와 복호화는 여기서 한 번)
    org.mockito.Mockito.clearInvocations(repository, encryption);
    Connection ddl = directConnection();
    when(factory.openDirect(any(ExternalDbTarget.class), eq("owner1"), eq("plain-pw")))
        .thenReturn(ddl);

    connections.withDdl(1, c -> 1);

    // 롤백 보상·커밋 뒤 삭제는 PG 트랜잭션이 끝난 뒤 불리므로 PG 를 다시 읽지 않는다.
    verify(repository, never()).find(anyLong());
    verify(repository, never()).findAccounts(anyLong());
    verify(encryption, never()).decrypt(anyString());
    verify(ddl).close();
  }

  @Test
  void withDdl_sessionInitFails_closesConnection_noWork() throws Exception {
    ready(1);
    Connection ddl = mock(Connection.class);
    Statement init = mock(Statement.class);
    when(ddl.createStatement()).thenReturn(init);
    when(init.execute(anyString())).thenThrow(new SQLException("init boom", "42000", 6550));
    when(factory.openDirect(any(ExternalDbTarget.class), anyString(), anyString())).thenReturn(ddl);
    AtomicBoolean ran = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                connections.withDdl(
                    1,
                    c -> {
                      ran.set(true);
                      return 1;
                    }))
        .isInstanceOf(DataAccessException.class);

    assertThat(ran).isFalse();
    verify(ddl).close();
  }

  @Test
  void withDml_afterCommit_doesNotReuseCommittedBoundConnection() throws Exception {
    expectClean = true;
    ready(1);
    Connection bound = mock(Connection.class);
    Connection later = mock(Connection.class);
    pool(1, bound, later);
    TransactionTemplate tx = template(TransactionDefinition.PROPAGATION_REQUIRED);
    List<Connection> seenAfterCommit = new java.util.ArrayList<>();

    tx.execute(
        s -> {
          connections.withDml(1, c -> 0);
          // @TransactionalEventListener(AFTER_COMMIT) 처럼 커밋 뒤에 Tibero 를 다시 읽는 콜백.
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  connections.withDml(
                      1,
                      c -> {
                        seenAfterCommit.add(c);
                        return 0;
                      });
                }
              });
          return null;
        });

    // 이미 커밋된 묶인 커넥션을 다시 쓰지 않고 자동 커밋 커넥션을 따로 열어 쓰고 닫았다.
    assertThat(seenAfterCommit).containsExactly(later);
    verify(later).setAutoCommit(true);
    verify(later).close();
    verify(bound, times(1)).commit();
    verify(bound).close();
  }

  @Test
  void pending_store_409() {
    when(repository.find(1))
        .thenReturn(
            Optional.of(
                new TenantDataStoreRow(
                    1,
                    DataStoreBackend.TIBERO,
                    "h",
                    8629,
                    "tb",
                    "S",
                    null,
                    DataStoreProvisionMode.DBA_AUTO,
                    TenantDataStoreStatus.PENDING)));

    assertThatThrownBy(() -> connections.withDml(1, x -> 1))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo("DATA_STORE_NOT_READY");
            });
  }

  @Test
  void missingStore_illegalState() {
    when(repository.find(anyLong())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> connections.schemaOf(9)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void readyStore_buildsPoolOnce_andSchemaCached() throws Exception {
    ready(1);
    pool(1, mock(Connection.class), mock(Connection.class));

    connections.withDml(1, x -> 1);
    connections.withDml(1, x -> 1);

    assertThat(connections.schemaOf(1)).isEqualTo("SCH1");
    verify(factory, times(1))
        .create(eq(1L), any(ExternalDbTarget.class), eq("owner1"), eq("plain-pw"));
    verify(repository, times(1)).find(1);
  }

  @Test
  void lruEvictsIdlePoolBeyondMax() throws Exception {
    ready(1);
    ready(2);
    ready(3);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    CloseableDataSource p2 = pool(2, mock(Connection.class));
    CloseableDataSource p3 = pool(3, mock(Connection.class));

    connections.withDml(1, x -> 1);
    connections.withDml(2, x -> 1);
    connections.withDml(3, x -> 1);

    verify(p1).close();
    verify(p2, never()).close();
    verify(p3, never()).close();
  }

  @Test
  void leasedBoundPoolIsNotEvicted() throws Exception {
    ready(1);
    ready(2);
    ready(3);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    pool(2, mock(Connection.class));
    pool(3, mock(Connection.class));
    beginTransaction();
    connections.withDml(1, x -> 1); // 트랜잭션에 묶여 pool 1 이 대여 중

    TransactionSynchronizationManager.setActualTransactionActive(false);
    connections.withDml(2, x -> 1);
    connections.withDml(3, x -> 1);

    verify(p1, never()).close();
  }

  @Test
  void sqlException_wrappedAsDataAccessException_keepsCause() throws Exception {
    // 드라이버 메시지(ORA-...)는 그대로 포함된다. 이 컴포넌트는 SQL 문장을 만들지 않으므로 SQL 은 싣지 않는다(sql=null).
    ready(1);
    pool(1, mock(Connection.class));
    SQLException cause = new SQLException("ORA-00942 table or view does not exist");

    assertThatThrownBy(
            () ->
                connections.withDml(
                    1,
                    c -> {
                      throw cause;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasCause(cause);
  }

  @Test
  void nonTiberoStore_illegalState() {
    when(repository.find(1))
        .thenReturn(
            Optional.of(
                new TenantDataStoreRow(
                    1,
                    DataStoreBackend.MYSQL,
                    "h",
                    3306,
                    "db",
                    "S",
                    null,
                    DataStoreProvisionMode.DBA_AUTO,
                    TenantDataStoreStatus.READY)));

    assertThatThrownBy(() -> connections.withDml(1, x -> 1))
        .isInstanceOf(IllegalStateException.class);
    verify(factory, never()).create(anyLong(), any(), anyString(), anyString());
  }

  @Test
  void requiresNew_usesSeparateConnection_commitsInnerBeforeOuter() throws Exception {
    expectClean = true;
    ready(1);
    Connection a = mock(Connection.class);
    Connection b = mock(Connection.class);
    CloseableDataSource ds = pool(1, a, b);
    TransactionTemplate outer = template(TransactionDefinition.PROPAGATION_REQUIRED);
    TransactionTemplate inner = template(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    List<Connection> seen = new java.util.ArrayList<>();

    outer.execute(
        s1 -> {
          connections.withDml(
              1,
              c -> {
                seen.add(c);
                return 0;
              });
          inner.execute(
              s2 ->
                  connections.withDml(
                      1,
                      c -> {
                        seen.add(c);
                        return 0;
                      }));
          // 안쪽 트랜잭션이 끝난 시점: B 는 커밋·종료됐고 A 는 아직이다.
          try {
            verify(b).commit();
            verify(b).close();
            verify(a, never()).commit();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          connections.withDml(
              1,
              c -> {
                seen.add(c);
                return 0;
              });
          return null;
        });

    assertThat(seen).containsExactly(a, b, a);
    verify(ds, times(2)).getConnection();
    InOrder order = inOrder(b, a);
    order.verify(b).commit();
    order.verify(a).commit();
    verify(a).close();
  }

  @Test
  void requiresNew_innerRollback_outerStillCommits() throws Exception {
    expectClean = true;
    ready(1);
    Connection a = mock(Connection.class);
    Connection b = mock(Connection.class);
    pool(1, a, b);
    TransactionTemplate outer = template(TransactionDefinition.PROPAGATION_REQUIRED);
    TransactionTemplate inner = template(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    outer.execute(
        s1 -> {
          connections.withDml(1, c -> 0);
          assertThatThrownBy(
                  () ->
                      inner.execute(
                          s2 -> {
                            connections.withDml(1, c -> 0);
                            throw new IllegalArgumentException("inner fail");
                          }))
              .isInstanceOf(IllegalArgumentException.class);
          return null;
        });

    verify(b).rollback();
    verify(b, never()).commit();
    verify(b).close();
    verify(a).commit();
    verify(a, never()).rollback();
  }

  @Test
  void realTransaction_commitFailure_rollsBackAndReleasesLease() throws Exception {
    expectClean = true;
    ready(1);
    Connection a = mock(Connection.class);
    CloseableDataSource p1 = pool(1, a);
    doThrow(new SQLException("commit boom")).when(a).commit();
    TransactionTemplate outer = template(TransactionDefinition.PROPAGATION_REQUIRED);

    assertThatThrownBy(() -> outer.execute(s -> connections.withDml(1, c -> 0)))
        .isInstanceOf(DataAccessException.class);

    verify(a).rollback();
    verify(a).close();
    assertPool1Evictable(p1);
  }

  @Test
  void realTransaction_leaseReleasedAfterCompletion() throws Exception {
    expectClean = true;
    ready(1);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    template(TransactionDefinition.PROPAGATION_REQUIRED)
        .execute(s -> connections.withDml(1, c -> 0));

    assertPool1Evictable(p1);
  }

  @Test
  void bound_getConnectionFails_releasesLease() throws Exception {
    expectClean = true;
    ready(1);
    CloseableDataSource p1 = mock(CloseableDataSource.class);
    when(p1.getConnection()).thenThrow(new SQLException("no conn"));
    when(factory.create(eq(1L), any(ExternalDbTarget.class), anyString(), eq("plain-pw")))
        .thenReturn(p1);
    TransactionTemplate outer = template(TransactionDefinition.PROPAGATION_REQUIRED);

    assertThatThrownBy(() -> outer.execute(s -> connections.withDml(1, c -> 0)))
        .isInstanceOf(DataAccessException.class);

    assertPool1Evictable(p1);
  }

  @Test
  void autoCommit_workFails_closesConnectionAndReleasesLease() throws Exception {
    ready(1);
    Connection c = mock(Connection.class);
    CloseableDataSource p1 = pool(1, c);

    assertThatThrownBy(
            () ->
                connections.withDml(
                    1,
                    x -> {
                      throw new SQLException("boom");
                    }))
        .isInstanceOf(DataAccessException.class);

    verify(c).close();
    assertPool1Evictable(p1);
  }

  // ---------- 롤백 보상 전 묶인 커넥션 정리(A5) ----------

  @Test
  void rollbackBound_releasesNow_andLaterAfterCompletionIsNoOp() throws Exception {
    expectClean = true;
    ready(1);
    Connection c = mock(Connection.class);
    CloseableDataSource p1 = pool(1, c);
    beginTransaction();
    connections.withDml(1, x -> 1);
    TransactionSynchronization sync = syncs().get(0);

    connections.rollbackBound(1);

    InOrder order = inOrder(c);
    order.verify(c).rollback();
    order.verify(c).close();
    // 묶음이 풀렸으므로 이후 같은 트랜잭션의 조회는 새 커넥션을 받는다(여기서는 확인만).
    assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    // 멱등: 두 번 불러도, 뒤이은 afterCompletion 도 아무것도 하지 않는다(이중 반납·이중 종료 없음).
    connections.rollbackBound(1);
    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(c, times(1)).rollback();
    verify(c, times(1)).close();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    assertPool1Evictable(p1);
  }

  @Test
  void rollbackBound_nothingBound_doesNothing() throws Exception {
    ready(1);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    beginTransaction();

    connections.rollbackBound(1);

    verify(p1, never()).getConnection();
    verify(factory, never()).create(anyLong(), any(), anyString(), anyString());
  }

  @Test
  void rollbackBound_committedConnection_leftToAfterCompletion() throws Exception {
    // beforeCommit 으로 이미 커밋된 커넥션은 잠금이 풀렸으므로 건드리지 않는다.
    ready(1);
    Connection c = mock(Connection.class);
    pool(1, c);
    beginTransaction();
    connections.withDml(1, x -> 1);
    TransactionSynchronization sync = syncs().get(0);
    sync.beforeCommit(false);

    connections.rollbackBound(1);
    verify(c, never()).close();

    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(c, never()).rollback();
    verify(c).close();
  }

  // ---------- 오류 번역(A2) ----------

  /** 작업이 주어진 SQLException 을 던질 때 밖으로 나오는 예외. */
  private Throwable failWith(SQLException cause) throws Exception {
    ready(1);
    pool(1, mock(Connection.class));
    return org.assertj.core.api.Assertions.catchThrowable(
        () ->
            connections.withDml(
                1,
                c -> {
                  throw cause;
                }));
  }

  @Test
  void translate_uniqueViolation_isDuplicateKey() throws Exception {
    Throwable e = failWith(new SQLException("ORA-00001: unique constraint violated", "23000", 1));
    assertThat(e).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(e.getCause()).isInstanceOf(SQLException.class);
  }

  @Test
  void translate_notNullAndTooLarge_areDataIntegrityViolation() throws Exception {
    for (int code : new int[] {1400, 2296, 12899}) {
      Throwable e = failWith(new SQLException("ORA-" + code + " x", "23000", code));
      assertThat(e)
          .as("code %d", code)
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
          .isNotInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
  }

  @Test
  void translate_messageTagOnly_stillRecognised() throws Exception {
    // 드라이버가 코드를 비워 두고 메시지에만 ORA 표기를 싣는 경우.
    Throwable e = failWith(new SQLException("ORA-12899: value too large for column"));
    assertThat(e).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void translate_unknownCode_isDataAccessException_withoutSql() throws Exception {
    Throwable e = failWith(new SQLException("ORA-99999: something odd", "HY000", 99999));
    assertThat(e)
        .isInstanceOf(DataAccessException.class)
        .isNotInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    // sql 인자는 null — 메시지에 SQL 문장이 실리지 않는다.
    assertThat(e.getMessage()).doesNotContain("SQL [");
    for (int code : new int[] {1, 1400}) {
      Throwable t = failWith(new SQLException("ORA-x", "23000", code));
      assertThat(t.getMessage()).doesNotContain("SQL [");
    }
  }

  @Test
  void closeAll_closesPools() throws Exception {
    ready(1);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    connections.withDml(1, x -> 1);

    connections.closeAll();

    verify(p1).close();
  }

  // ---------- 잠금 범위·단발 경로(WD-74 simplify) ----------

  @Test
  void concurrentMiss_readsOutsideLock_buildsExactlyOnePool() throws Exception {
    ready(1);
    pool(1, mock(Connection.class), mock(Connection.class));
    Optional<TenantDataStoreRow> row = repository.find(1L);
    CountDownLatch bothReading = new CountDownLatch(2);
    AtomicBoolean readUnderLock = new AtomicBoolean();
    when(repository.find(1L))
        .thenAnswer(
            inv -> {
              readUnderLock.compareAndSet(false, Thread.holdsLock(connections));
              bothReading.countDown();
              // 두 스레드가 동시에 이 구간에 있어야 진행한다 — 읽기가 전역 잠금 안이었다면 둘째 스레드가 못 들어와 시간 초과로 끝난다.
              bothReading.await(5, TimeUnit.SECONDS);
              return row;
            });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> a = pool.submit(() -> connections.withDml(1, x -> 1));
      Future<Integer> b = pool.submit(() -> connections.withDml(1, x -> 1));
      assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    assertThat(bothReading.getCount()).as("두 스레드 모두 잠금 밖에서 동시에 읽었다").isZero();
    assertThat(readUnderLock).as("저장소 읽기 중 전역 잠금을 쥐었는지").isFalse();
    // 다시 확인 덕분에 풀은 하나만 만든다.
    verify(factory, times(1))
        .create(eq(1L), any(ExternalDbTarget.class), eq("owner1"), eq("plain-pw"));
  }

  @Test
  void eviction_closesVictimAfterLeavingLock() throws Exception {
    ready(1);
    ready(2);
    ready(3);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    AtomicBoolean closedUnderLock = new AtomicBoolean(true);
    // 단언을 close 안에서 하면 closeQuietly 가 삼키므로 관측값만 기록한다.
    doAnswer(
            inv -> {
              closedUnderLock.set(Thread.holdsLock(connections));
              return null;
            })
        .when(p1)
        .close();
    pool(2, mock(Connection.class));
    pool(3, mock(Connection.class));

    connections.withDml(1, x -> 1);
    connections.withDml(2, x -> 1);
    connections.withDml(3, x -> 1);

    verify(p1).close();
    assertThat(closedUnderLock).as("축출한 풀을 전역 잠금 안에서 닫았는지").isFalse();
  }

  @Test
  void withDdl_noPool_opensDirectConnection_insertsAndEvictsNothing() throws Exception {
    ready(1);
    ready(2);
    ready(3);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    CloseableDataSource p2 = pool(2, mock(Connection.class));
    connections.withDml(1, x -> 1);
    connections.withDml(2, x -> 1); // 상한(2) 가득
    Connection direct = directConnection();
    when(factory.openDirect(any(ExternalDbTarget.class), eq("owner3"), eq("plain-pw")))
        .thenReturn(direct);

    int result =
        connections.withDdl(
            3,
            c -> {
              assertThat(c).isSameAs(direct);
              return 7;
            });

    assertThat(result).isEqualTo(7);
    verify(direct).setAutoCommit(true);
    verify(direct).close();
    // 풀을 만들지도, 다른 테넌트의 풀을 밀어내지도 않는다.
    verify(factory, never()).create(eq(3L), any(ExternalDbTarget.class), anyString(), anyString());
    verify(p1, never()).close();
    verify(p2, never()).close();
    // 풀 목록에 아무것도 넣지 않았다 — 이후 일반 경로가 처음으로 풀을 만든다.
    pool(3, mock(Connection.class));
    connections.withDml(3, x -> 1);
    verify(factory).create(eq(3L), any(ExternalDbTarget.class), eq("owner3"), eq("plain-pw"));
  }

  @Test
  void withDdl_existingPool_notLeased_lruOrderUnchanged() throws Exception {
    ready(1);
    ready(2);
    ready(3);
    CloseableDataSource p1 = pool(1, mock(Connection.class));
    CloseableDataSource p2 = pool(2, mock(Connection.class));
    connections.withDml(1, x -> 1);
    connections.withDml(2, x -> 1); // LRU: 1(가장 오래됨) → 2
    Connection direct = directConnection();
    when(factory.openDirect(any(ExternalDbTarget.class), eq("owner1"), eq("plain-pw")))
        .thenReturn(direct);

    connections.withDdl(1, c -> 1);

    // 풀이 있어도 풀에서 꺼내지 않고 단발 직결 커넥션을 쓰고 닫는다.
    verify(p1, times(1)).getConnection();
    verify(direct).setAutoCommit(true);
    verify(direct).close();
    // LRU 순서가 그대로라 다음 축출 대상은 여전히 풀 1 이다(get 으로 읽었다면 풀 2 가 밀려났다).
    pool(3, mock(Connection.class));
    connections.withDml(3, x -> 1);
    verify(p1).close();
    verify(p2, never()).close();
  }

  @Test
  void withDdl_directOpenFails_resourceFailureWithoutSecret() throws Exception {
    ready(1);
    when(factory.openDirect(any(ExternalDbTarget.class), anyString(), anyString()))
        .thenThrow(new ExternalDbConnectionException("연결 시간이 초과되었습니다(10초)"));

    assertThatThrownBy(() -> connections.withDdl(1, c -> 1))
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessage("연결 시간이 초과되었습니다(10초)")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("plain-pw"));
  }

  @Test
  void schemaOfTransient_noPool_readsRow_withoutBuildingPoolOrDecrypting() {
    ready(1);

    assertThat(connections.schemaOfTransient(1)).isEqualTo("SCH1");

    verify(factory, never())
        .create(anyLong(), any(ExternalDbTarget.class), anyString(), anyString());
    verify(encryption, never()).decrypt(anyString());
  }

  @Test
  void schemaOfTransient_pendingStore_409() {
    ready(1);
    TenantDataStoreRow ready = repository.find(1L).orElseThrow();
    when(repository.find(1L))
        .thenReturn(
            Optional.of(
                new TenantDataStoreRow(
                    1L,
                    ready.backend(),
                    ready.host(),
                    ready.port(),
                    ready.database(),
                    ready.schemaName(),
                    null,
                    DataStoreProvisionMode.DBA_AUTO,
                    TenantDataStoreStatus.PENDING)));

    assertThatThrownBy(() -> connections.schemaOfTransient(1))
        .isInstanceOfSatisfying(
            CodedApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
  }
}

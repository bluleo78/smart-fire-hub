package com.smartfirehub.global.datastore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import org.jooq.Transaction;
import org.jooq.TransactionContext;
import org.jooq.TransactionProvider;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DefaultTransactionProvider;

/**
 * 분리 모드 데이터 저장소 DSLContext 의 jOOQ {@link TransactionProvider} — {@code dsl.transaction(...)} 의 의미를
 * 정한다(WD-494 스펙 2절).
 *
 * <ul>
 *   <li>실제 Spring 트랜잭션 안: {@link DataStoreConnectionBinding} 이 그 트랜잭션에 묶은 연결에 SAVEPOINT 를 건다.
 *       commit = SAVEPOINT 해제, rollback = SAVEPOINT 로 되돌리기. 최종 커밋·롤백은 플랫폼 트랜잭션의 동기화가 한다 — PG 설치본에서
 *       {@code SpringTransactionProvider} 가 하던 "바깥 트랜잭션에 참여" 와 같은 결과다.
 *   <li>그 밖(트랜잭션 밖, 또는 묶음이 이미 커밋된 완료 단계): jOOQ {@link DefaultTransactionProvider} 에 위임해 바인딩에서 꺼낸 자동
 *       커밋 연결로 자체 로컬 트랜잭션을 연다. 그 안의 중첩 {@code dsl.transaction} 은 기본 제공자가 SAVEPOINT 로 처리한다.
 * </ul>
 *
 * <p>어느 경로인지는 {@link #begin} 에서 한 번 정하고 컨텍스트의 {@link TransactionContext#transaction()} 에 표시한다 —
 * commit·rollback 시점에 Spring 상태를 다시 보면 그 사이 상태가 바뀐 경우(예: 람다 안에서 다른 트랜잭션 경계) 짝이 어긋날 수 있다.
 */
public class DataStoreTransactionProvider implements TransactionProvider {

  private final DataStoreConnectionBinding binding;
  private final DefaultTransactionProvider local;

  public DataStoreTransactionProvider(DataStoreConnectionBinding binding) {
    this.binding = binding;
    this.local = new DefaultTransactionProvider(binding);
  }

  /** Spring 트랜잭션 경로의 표시 — 묶인 연결과 이 jOOQ 트랜잭션이 건 SAVEPOINT(아직 못 걸었으면 null). */
  private static final class SavepointTransaction implements Transaction {
    final Connection connection;
    Savepoint savepoint;

    SavepointTransaction(Connection connection) {
      this.connection = connection;
    }
  }

  @Override
  public void begin(TransactionContext ctx) {
    Connection bound = binding.boundConnectionOrNull();
    if (bound == null) {
      local.begin(ctx);
      return;
    }
    // SAVEPOINT 를 걸기 전에 표시부터 한다 — 걸다 실패해도 jOOQ 가 부르는 rollback 이 로컬 제공자로 새지 않게.
    SavepointTransaction tx = new SavepointTransaction(bound);
    ctx.transaction(tx);
    try {
      tx.savepoint = bound.setSavepoint();
    } catch (SQLException e) {
      throw new DataAccessException("데이터 저장소 SAVEPOINT 생성 실패", e);
    }
  }

  @Override
  public void commit(TransactionContext ctx) {
    if (ctx.transaction() instanceof SavepointTransaction tx) {
      try {
        tx.connection.releaseSavepoint(tx.savepoint);
      } catch (SQLException e) {
        throw new DataAccessException("데이터 저장소 SAVEPOINT 해제 실패", e);
      }
      return;
    }
    local.commit(ctx);
  }

  @Override
  public void rollback(TransactionContext ctx) {
    if (ctx.transaction() instanceof SavepointTransaction tx) {
      if (tx.savepoint == null) {
        // SAVEPOINT 를 걸지 못했다 — 되돌릴 것이 없다. 바깥 트랜잭션의 처리는 플랫폼 동기화에 맡긴다.
        return;
      }
      try {
        tx.connection.rollback(tx.savepoint);
      } catch (SQLException e) {
        throw new DataAccessException("데이터 저장소 SAVEPOINT 롤백 실패", e);
      }
      return;
    }
    local.rollback(ctx);
  }
}

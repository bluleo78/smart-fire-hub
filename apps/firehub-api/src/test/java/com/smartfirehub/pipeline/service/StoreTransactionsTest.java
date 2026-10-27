package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link StoreTransactions} 단위 테스트(WD-231) — DB 없이 가짜 트랜잭션 관리자로 "호출 순간 트랜잭션이 열려 있었는가"와 트랜잭션이 몇 번 열려
 * 어떻게 끝났는지를 기록해, PG 는 한 트랜잭션·Tibero 는 실행이 트랜잭션 밖이라는 약속을 확인한다.
 *
 * <p>세 템플릿(pgTx·tiberoLoadTx·afterTx)은 이름과 readOnly 가 서로 달라서, 각 단계가 <b>기대한 템플릿</b>으로 감싸졌는지(템플릿이 바뀌어
 * 끼워졌는지)까지 기록으로 검증한다.
 */
class StoreTransactionsTest {

  /** 열기·커밋·롤백 횟수를 세는 가짜 관리자. 동기화는 부모 클래스가 켜 주므로 isActualTransactionActive 가 실제로 동작한다. */
  static class RecordingTxManager extends AbstractPlatformTransactionManager {
    int begun;
    int committed;
    int rolledBack;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begun++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      committed++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rolledBack++;
    }
  }

  private RecordingTxManager manager;
  private TransactionTemplate pgTx;
  private TransactionTemplate tiberoLoadTx;
  private TransactionTemplate afterTx;
  private PipelineDataSchema pgSchema;
  private PipelineDataSchema tiberoSchema;

  /** 호출 단계별 "트랜잭션 이름(없으면 none)·readOnly·begin 누적 횟수"를 순서대로 담는다. */
  private final List<String> log = new ArrayList<>();

  @BeforeEach
  void setUp() {
    manager = new RecordingTxManager();
    // 템플릿마다 이름과 readOnly 를 다르게 해서, 단계가 어느 템플릿으로 감싸졌는지 구별한다.
    pgTx = new TransactionTemplate(manager);
    pgTx.setName("pg");
    tiberoLoadTx = new TransactionTemplate(manager);
    tiberoLoadTx.setName("load");
    tiberoLoadTx.setReadOnly(true);
    afterTx = new TransactionTemplate(manager);
    afterTx.setName("after");
    pgSchema = mock(PipelineDataSchema.class);
    when(pgSchema.isTibero()).thenReturn(false);
    tiberoSchema = mock(PipelineDataSchema.class);
    when(tiberoSchema.isTibero()).thenReturn(true);
  }

  /** 지금 열린 트랜잭션의 이름·readOnly·begin 횟수를 "단계:이름/ro|rw@begun=n" 형태로 남긴다. 트랜잭션이 없으면 이름은 none. */
  private void record(String stage) {
    boolean active = TransactionSynchronizationManager.isActualTransactionActive();
    String name = active ? TransactionSynchronizationManager.getCurrentTransactionName() : "none";
    String mode =
        active
            ? (TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "ro" : "rw")
            : "-";
    log.add(stage + ":" + name + "/" + mode + "@begun=" + manager.begun);
  }

  @Test
  void pg_runsLoadExecuteAfter_inOneTransaction() {
    String result =
        StoreTransactions.loadExecuteThen(
            pgSchema,
            pgTx,
            tiberoLoadTx,
            () -> {
              record("load");
              return 1;
            },
            l -> {
              record("execute");
              return l + 1;
            },
            afterTx,
            r -> {
              record("after");
              return "r" + r;
            });

    assertThat(result).isEqualTo("r2");
    // 세 단계 모두 pgTx 로 연 같은 트랜잭션(begin 1 회) 안이다 — tiberoLoadTx·afterTx 는 쓰이지 않는다.
    assertThat(log)
        .containsExactly("load:pg/rw@begun=1", "execute:pg/rw@begun=1", "after:pg/rw@begun=1");
    assertThat(manager.begun).isEqualTo(1);
    assertThat(manager.committed).isEqualTo(1);
  }

  @Test
  void tibero_loadInTx_executeOutside_afterInSeparateTx() {
    String result =
        StoreTransactions.loadExecuteThen(
            tiberoSchema,
            pgTx,
            tiberoLoadTx,
            () -> {
              record("load");
              return 1;
            },
            l -> {
              record("execute");
              return l + 1;
            },
            afterTx,
            r -> {
              record("after");
              return "r" + r;
            });

    assertThat(result).isEqualTo("r2");
    // load 는 tiberoLoadTx(readOnly) 안, execute 는 트랜잭션 밖, after 는 afterTx 로 연 새 트랜잭션 안이다. pgTx 는 쓰이지
    // 않는다.
    assertThat(log)
        .containsExactly(
            "load:load/ro@begun=1", "execute:none/-@begun=1", "after:after/rw@begun=2");
    assertThat(manager.committed).isEqualTo(2);

    // loadThenExecute 도 같은 모양: 적재만 tiberoLoadTx, 실행은 밖.
    log.clear();
    manager.begun = 0;
    Integer r2 =
        StoreTransactions.loadThenExecute(
            tiberoSchema,
            pgTx,
            tiberoLoadTx,
            () -> {
              record("load");
              return 5;
            },
            l -> {
              record("execute");
              return l * 2;
            });
    assertThat(r2).isEqualTo(10);
    assertThat(log).containsExactly("load:load/ro@begun=1", "execute:none/-@begun=1");
  }

  @Test
  void tibero_nullLoadTx_loadsWithoutTransaction() {
    Integer result =
        StoreTransactions.loadThenExecute(
            tiberoSchema,
            pgTx,
            null,
            () -> {
              record("load");
              return 3;
            },
            l -> {
              record("execute");
              return l + 1;
            });

    assertThat(result).isEqualTo(4);
    // tiberoLoadTx 가 null 이면 적재도 트랜잭션 없이 돈다 — 트랜잭션은 한 번도 열리지 않는다.
    assertThat(log).containsExactly("load:none/-@begun=0", "execute:none/-@begun=0");
    assertThat(manager.begun).isZero();
  }

  @Test
  void tibero_loadExecuteThen_nullLoadTx_loadAndExecuteOutside_afterInAfterTx() {
    // DatasetDataService 의 조합: 조회 템플릿 없음 + 후처리만 쓰기 트랜잭션.
    String result =
        StoreTransactions.loadExecuteThen(
            tiberoSchema,
            pgTx,
            null,
            () -> {
              record("load");
              return 1;
            },
            l -> {
              record("execute");
              return l + 1;
            },
            afterTx,
            r -> {
              record("after");
              return "r" + r;
            });

    assertThat(result).isEqualTo("r2");
    // load·execute 는 트랜잭션 밖, after 만 afterTx 로 연 트랜잭션 하나(begin 1 회) 안이다.
    assertThat(log)
        .containsExactly("load:none/-@begun=0", "execute:none/-@begun=0", "after:after/rw@begun=1");
    assertThat(manager.begun).isEqualTo(1);
    assertThat(manager.committed).isEqualTo(1);
  }

  @Test
  void tibero_exceptionInExecute_afterNotRun_exceptionPropagates() {
    assertThatThrownBy(
            () ->
                StoreTransactions.loadExecuteThen(
                    tiberoSchema,
                    pgTx,
                    tiberoLoadTx,
                    () -> {
                      record("load");
                      return 1;
                    },
                    l -> {
                      record("execute");
                      throw new IllegalStateException("execute 실패");
                    },
                    afterTx,
                    r -> {
                      record("after");
                      return r;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("execute 실패");

    // 조회 트랜잭션은 이미 커밋됐고, 실행은 밖에서 던졌으며, 후처리 트랜잭션은 열리지 않는다.
    assertThat(log).containsExactly("load:load/ro@begun=1", "execute:none/-@begun=1");
    assertThat(manager.begun).isEqualTo(1);
    assertThat(manager.committed).isEqualTo(1);
    assertThat(manager.rolledBack).isZero();
  }

  @Test
  void pg_exceptionInExecute_rollsBackWholeUnit() {
    assertThatThrownBy(
            () ->
                StoreTransactions.loadExecuteThen(
                    pgSchema,
                    pgTx,
                    tiberoLoadTx,
                    () -> {
                      record("load");
                      return 1;
                    },
                    l -> {
                      throw new IllegalStateException("execute 실패");
                    },
                    afterTx,
                    r -> {
                      record("after");
                      return r;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("execute 실패");

    // 한 단위가 통째로 롤백되고 after 는 돌지 않는다.
    assertThat(manager.begun).isEqualTo(1);
    assertThat(manager.rolledBack).isEqualTo(1);
    assertThat(manager.committed).isZero();
    assertThat(log).containsExactly("load:pg/rw@begun=1");
  }
}

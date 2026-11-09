package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.support.FakeTransaction;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WD-675 — 데이터셋 삭제 잠금 증표({@link DatasetDeleteLock})가 트랜잭션 안에서만 행을 실제로 잠가 만들어지고, 만든 트랜잭션이 끝나면 더는 쓸 수
 * 없는지 본다.
 *
 * <p>WD-680 — 같은 id 를 두 번 넘겨도 한 번만 잠그고, 만든 트랜잭션이 살아 있어도 다른 트랜잭션(REQUIRES_NEW 안쪽·다른 스레드)에서는 쓸 수 없으며,
 * 트랜잭션 밖 거부 문구가 「삭제 잠금」인지 본다.
 */
class DatasetDeleteLockTest {

  /**
   * DB 없는 실제 트랜잭션 관리자(WD-680) — REQUIRES_NEW 의 일시 중단(suspend)·재개(resume)를 Spring 부모 클래스가 실제로 돌리게
   * 한다. {@link FakeTransaction} 은 중단을 흉내 내지 못한다. 열린 트랜잭션 수를 쌓아 두어 REQUIRED 는 합류, REQUIRES_NEW 는 바깥을
   * 중단하고 새로 연다.
   */
  static class InMemoryTxManager extends AbstractPlatformTransactionManager {
    private final Deque<Object> open = new ArrayDeque<>();

    @Override
    protected Object doGetTransaction() {
      return new boolean[] {!open.isEmpty()};
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return ((boolean[]) transaction)[0];
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      open.push(transaction);
    }

    @Override
    protected Object doSuspend(Object transaction) {
      return transaction;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
      open.pop();
    }
  }

  private final InMemoryTxManager txManager = new InMemoryTxManager();

  private final DatasetRepository datasetRepository = mock(DatasetRepository.class);

  @AfterEach
  void clearTransaction() {
    FakeTransaction.end();
  }

  @Test
  void 트랜잭션_밖에서는_잠그지도_만들지도_않는다() {
    // WD-680: 거부 문구가 쓰기 잠금이 아니라 삭제 잠금을 말한다
    assertThatThrownBy(() -> DatasetDeleteLock.acquire(datasetRepository, List.of(10L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("데이터셋 삭제 잠금은")
        .hasMessageNotContaining("쓰기 잠금");
    verifyNoInteractions(datasetRepository);
  }

  @Test
  void 같은_id_를_두_번_넘겨도_한_번만_잠그고_증표에도_한_번만_든다() {
    // WD-680: 중복 id 가 증표에 두 번 들면 삭제 본문이 같은 데이터셋을 두 번 지우려다(두 번째 DROP·행 삭제) 실패한다
    FakeTransaction.begin();
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);

    DatasetDeleteLock lock =
        DatasetDeleteLock.acquire(datasetRepository, List.of(20L, 10L, 20L, 10L));

    verify(datasetRepository, times(1)).lockForDelete(10L);
    verify(datasetRepository, times(1)).lockForDelete(20L);
    assertThat(lock.lockedIds()).containsExactly(10L, 20L);
  }

  @Test
  void 바깥_트랜잭션이_살아_있어도_REQUIRES_NEW_안쪽에서는_쓸_수_없고_돌아오면_다시_쓸_수_있다() {
    // WD-680: 안쪽 트랜잭션은 바깥의 FOR UPDATE 를 쥐지 않아 같은 행에서 잠금 시간 초과까지 기다린다 — 바로 거부해야 한다
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);
    TransactionTemplate outer = new TransactionTemplate(txManager);
    TransactionTemplate inner = new TransactionTemplate(txManager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TransactionTemplate joined = new TransactionTemplate(txManager);

    DatasetDeleteLock lock =
        outer.execute(
            status -> {
              DatasetDeleteLock created =
                  DatasetDeleteLock.acquire(datasetRepository, List.of(10L));
              assertThatThrownBy(() -> inner.executeWithoutResult(s -> created.requireActive()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("다른 트랜잭션");
              // 안쪽이 끝나고 바깥이 재개되면 같은 트랜잭션이므로 다시 쓸 수 있다(REQUIRED 합류도 같은 트랜잭션)
              created.requireActive();
              joined.executeWithoutResult(s -> created.requireActive());
              return created;
            });

    // 바깥이 커밋되면 잠금이 풀렸으므로 거부한다
    assertThatThrownBy(lock::requireActive).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void NOT_SUPPORTED_로_트랜잭션_밖에_나간_동안에도_쓸_수_없다() {
    // WD-680: NOT_SUPPORTED 도 REQUIRES_NEW 와 같은 일시 중단 경로다 — 그동안의 코드는 잠금을 쥔 트랜잭션 밖이다
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);
    TransactionTemplate outer = new TransactionTemplate(txManager);
    TransactionTemplate notSupported = new TransactionTemplate(txManager);
    notSupported.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

    outer.executeWithoutResult(
        status -> {
          DatasetDeleteLock lock = DatasetDeleteLock.acquire(datasetRepository, List.of(10L));
          assertThatThrownBy(() -> notSupported.executeWithoutResult(s -> lock.requireActive()))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("다른 트랜잭션");
          // 돌아오면 다시 만든 트랜잭션이다
          lock.requireActive();
        });
  }

  @Test
  void 다른_스레드에서는_쓸_수_없다() {
    // WD-680: Spring 트랜잭션은 스레드에 묶인다 — 다른 스레드는 곧 다른 트랜잭션(또는 트랜잭션 밖)이다
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);
    TransactionTemplate outer = new TransactionTemplate(txManager);

    outer.executeWithoutResult(
        status -> {
          DatasetDeleteLock lock = DatasetDeleteLock.acquire(datasetRepository, List.of(10L));
          CompletableFuture<Void> other = CompletableFuture.runAsync(lock::requireActive);
          assertThatThrownBy(other::get)
              .isInstanceOf(ExecutionException.class)
              .hasCauseInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  void 행을_id_오름차순으로_잠그고_지워진_행은_뺀다() {
    FakeTransaction.begin();
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);
    when(datasetRepository.lockForDelete(20L)).thenReturn(false);

    DatasetDeleteLock lock = DatasetDeleteLock.acquire(datasetRepository, List.of(30L, 20L, 10L));

    InOrder order = inOrder(datasetRepository);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(datasetRepository).lockForDelete(20L);
    order.verify(datasetRepository).lockForDelete(30L);
    assertThat(lock.lockedIds()).containsExactly(10L, 30L);
    lock.requireActive();
  }

  @Test
  void 만든_트랜잭션이_끝난_증표는_다시_쓸_수_없다() {
    // 끝난 트랜잭션의 증표를 다른 트랜잭션에서 쓰면 잠금이 풀린 채 지운다 — 완료 뒤에는 거부해야 한다
    FakeTransaction.begin();
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);
    DatasetDeleteLock lock = DatasetDeleteLock.acquire(datasetRepository, List.of(10L));

    FakeTransaction.complete();

    assertThatThrownBy(lock::requireActive).isInstanceOf(IllegalStateException.class);
  }
}

package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.support.FakeTransaction;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * WD-675 — 데이터셋 삭제 잠금 증표({@link DatasetDeleteLock})가 트랜잭션 안에서만 행을 실제로 잠가 만들어지고, 만든 트랜잭션이 끝나면 더는 쓸 수
 * 없는지 본다.
 */
class DatasetDeleteLockTest {

  private final DatasetRepository datasetRepository = mock(DatasetRepository.class);

  @AfterEach
  void clearTransaction() {
    FakeTransaction.end();
  }

  @Test
  void 트랜잭션_밖에서는_잠그지도_만들지도_않는다() {
    assertThatThrownBy(() -> DatasetDeleteLock.acquire(datasetRepository, List.of(10L)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(datasetRepository);
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

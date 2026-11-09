package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataimport.exception.ImportProcessingException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetWriteTarget;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * WD-674 — 가져오기 대상 쓰기({@code DataImportService.writeToTarget})의 「임포트 중 데이터셋이 삭제되어…」 안내는 <b>잠금을 잡을
 * 때</b> 데이터셋이 없을 때만 붙어야 한다. 예전에는 쓰기 본문 전체를 감싸 잡아, 본문 안에서 난 무관한 「데이터셋 없음」까지 삭제로 오인해 안내했다.
 *
 * <p>잠금 층은 실제 {@link DatasetDataWriteLock}(목 저장소·목 트랜잭션 관리자)이고, 트랜잭션 확인은 동기화 표시만 켜서 흉내 낸다.
 */
class DataImportWriteTargetTest {

  private static final long DATASET_ID = 42L;

  private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
  private final DatasetDataWriteLock writeLock =
      new DatasetDataWriteLock(datasetRepository, mock(PlatformTransactionManager.class));

  @BeforeEach
  void simulateActiveTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void clearTransactionFlag() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void 잠글_때_데이터셋이_없으면_본문을_실행하지_않고_삭제_안내로_실패한다() {
    when(datasetRepository.lockForDataWrite(DATASET_ID)).thenReturn(Optional.empty());
    AtomicBoolean ran = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                DataImportService.writeToTarget(
                    writeLock,
                    DATASET_ID,
                    () -> {
                      ran.set(true);
                      return null;
                    }))
        .isInstanceOf(ImportProcessingException.class)
        .hasMessage(DataImportService.DATASET_GONE_MESSAGE);
    assertThat(ran).isFalse();
  }

  @Test
  void 잠금을_잡은_뒤_본문에서_난_데이터셋_없음은_삭제_안내로_바꾸지_않는다() {
    when(datasetRepository.lockForDataWrite(DATASET_ID))
        .thenReturn(Optional.of(new DatasetWriteTarget(DATASET_ID, "ds", "t_ds", "TABLE")));
    DatasetNotFoundException fromBody = new DatasetNotFoundException();

    assertThatThrownBy(
            () ->
                DataImportService.writeToTarget(
                    writeLock,
                    DATASET_ID,
                    () -> {
                      throw fromBody;
                    }))
        .isSameAs(fromBody);
  }

  @Test
  void 잠금을_잡으면_본문_결과를_그대로_돌려준다() {
    when(datasetRepository.lockForDataWrite(DATASET_ID))
        .thenReturn(Optional.of(new DatasetWriteTarget(DATASET_ID, "ds", "t_ds", "TABLE")));

    assertThat(DataImportService.writeToTarget(writeLock, DATASET_ID, () -> 7L)).isEqualTo(7L);
  }
}

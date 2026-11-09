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
 *
 * <p>WD-675: 테스트용 정적 오버로드 대신 서비스가 넘기는 변환 상수({@code DataImportService.ON_DATASET_GONE})를 그대로 잠금 층에
 * 넘긴다. 변환이 null 을 돌려줄 때 원인을 밝힌 NullPointerException 으로 실패하는지도 본다.
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
                writeLock.writeToDataset(
                    DATASET_ID,
                    DataImportService.ON_DATASET_GONE,
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
                writeLock.writeToDataset(
                    DATASET_ID,
                    DataImportService.ON_DATASET_GONE,
                    () -> {
                      throw fromBody;
                    }))
        .isSameAs(fromBody);
  }

  @Test
  void 변환이_null_을_돌려주면_원인을_밝힌_NullPointerException_으로_실패한다() {
    // WD-675: 예전에는 `throw onGone.apply(e)` 가 메시지 없는 NPE 가 됐다.
    // 호출자 버그를 숨기지 않고 무엇이 null 인지 남긴다.
    when(datasetRepository.lockForDataWrite(DATASET_ID)).thenReturn(Optional.empty());
    AtomicBoolean ran = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                writeLock.writeToDataset(
                    DATASET_ID,
                    gone -> null,
                    () -> {
                      ran.set(true);
                      return null;
                    }))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("onGone");
    assertThat(ran).isFalse();
  }

  @Test
  void 잠금을_잡으면_본문_결과를_그대로_돌려준다() {
    when(datasetRepository.lockForDataWrite(DATASET_ID))
        .thenReturn(Optional.of(new DatasetWriteTarget(DATASET_ID, "ds", "t_ds", "TABLE")));

    assertThat(writeLock.writeToDataset(DATASET_ID, DataImportService.ON_DATASET_GONE, () -> 7L))
        .isEqualTo(7L);
  }
}

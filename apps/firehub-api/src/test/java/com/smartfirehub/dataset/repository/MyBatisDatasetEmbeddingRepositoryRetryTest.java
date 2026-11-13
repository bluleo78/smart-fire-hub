package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.mybatis.DatasetEmbeddingMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetEmbeddingRepository;
import com.smartfirehub.vectorstore.off.OffDatasetVectorIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatisDatasetEmbeddingRepository.upsertSourceText 의 DuplicateKeyException 1회 재시도 분기를 DB 없이
 * 고정한다(WD-707).
 */
class MyBatisDatasetEmbeddingRepositoryRetryTest {

  private final DatasetEmbeddingMapper mapper = mock(DatasetEmbeddingMapper.class);
  private final MyBatisDatasetEmbeddingRepository repository =
      new MyBatisDatasetEmbeddingRepository(mapper, new OffDatasetVectorIndex());

  @Test
  @DisplayName("첫 시도가 중복 키로 실패하면 한 번만 다시 시도한다(두 번째는 이미 있는 행을 만나 UPDATE)")
  void upsertSourceText_retriesOnceAfterDuplicateKey() {
    when(mapper.upsertSourceText(anyLong(), anyString()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(1);

    repository.upsertSourceText(1L, "본문");

    verify(mapper, times(2)).upsertSourceText(1L, "본문");
  }

  @Test
  @DisplayName("두 번 다 중복 키로 실패하면 예외가 삼켜지지 않고 그대로 올라간다")
  void upsertSourceText_propagatesWhenRetryAlsoFails() {
    when(mapper.upsertSourceText(anyLong(), anyString()))
        .thenThrow(new DuplicateKeyException("dup"));

    assertThatThrownBy(() -> repository.upsertSourceText(1L, "본문"))
        .isInstanceOf(DuplicateKeyException.class);

    verify(mapper, times(2)).upsertSourceText(1L, "본문");
  }

  @Test
  @DisplayName("첫 시도가 성공하면 다시 시도하지 않는다")
  void upsertSourceText_noRetryOnSuccess() {
    when(mapper.upsertSourceText(anyLong(), anyString())).thenReturn(1);

    repository.upsertSourceText(1L, "본문");

    verify(mapper, times(1)).upsertSourceText(1L, "본문");
  }
}

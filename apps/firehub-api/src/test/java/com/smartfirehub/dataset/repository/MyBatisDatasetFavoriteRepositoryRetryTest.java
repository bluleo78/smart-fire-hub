package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.mybatis.DatasetFavoriteMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetFavoriteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** MyBatisDatasetFavoriteRepository 의 DuplicateKeyException 1회 재시도 분기를 DB 없이 고정한다(WD-707). */
class MyBatisDatasetFavoriteRepositoryRetryTest {

  private final DatasetFavoriteMapper mapper = mock(DatasetFavoriteMapper.class);
  private final MyBatisDatasetFavoriteRepository repository =
      new MyBatisDatasetFavoriteRepository(mapper);

  @Test
  @DisplayName("첫 시도가 중복 키로 실패하면 한 번만 다시 시도하고 예외 없이 끝난다")
  void insert_retriesOnceAfterDuplicateKey() {
    when(mapper.insertIfAbsent(anyLong(), anyLong()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(0);

    repository.insert(1L, 2L);

    verify(mapper, times(2)).insertIfAbsent(1L, 2L);
  }

  @Test
  @DisplayName("두 번 다 중복 키로 실패하면 예외가 삼켜지지 않고 그대로 올라간다")
  void insert_propagatesWhenRetryAlsoFails() {
    when(mapper.insertIfAbsent(anyLong(), anyLong())).thenThrow(new DuplicateKeyException("dup"));

    assertThatThrownBy(() -> repository.insert(1L, 2L)).isInstanceOf(DuplicateKeyException.class);

    verify(mapper, times(2)).insertIfAbsent(1L, 2L);
  }

  @Test
  @DisplayName("첫 시도가 성공하면 다시 시도하지 않는다")
  void insert_noRetryOnSuccess() {
    when(mapper.insertIfAbsent(anyLong(), anyLong())).thenReturn(1);

    repository.insert(1L, 2L);

    verify(mapper, times(1)).insertIfAbsent(1L, 2L);
  }
}

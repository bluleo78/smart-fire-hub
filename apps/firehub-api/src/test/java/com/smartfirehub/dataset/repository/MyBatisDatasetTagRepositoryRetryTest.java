package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.mybatis.DatasetTagMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetTagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** MyBatisDatasetTagRepository 의 DuplicateKeyException 1회 재시도 분기를 DB 없이 고정한다(WD-707). */
class MyBatisDatasetTagRepositoryRetryTest {

  private final DatasetTagMapper mapper = mock(DatasetTagMapper.class);
  private final MyBatisDatasetTagRepository repository = new MyBatisDatasetTagRepository(mapper);

  @Test
  @DisplayName("첫 시도가 중복 키로 실패하면 한 번만 다시 시도해 그 결과를 돌려준다")
  void insert_retriesOnceAfterDuplicateKey() {
    when(mapper.insertIfAbsent(anyLong(), anyString(), any()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(0);

    assertThat(repository.insert(1L, "fire", 2L)).isZero();

    verify(mapper, times(2)).insertIfAbsent(1L, "fire", 2L);
  }

  @Test
  @DisplayName("두 번 다 중복 키로 실패하면 예외가 삼켜지지 않고 그대로 올라간다")
  void insert_propagatesWhenRetryAlsoFails() {
    when(mapper.insertIfAbsent(anyLong(), anyString(), any()))
        .thenThrow(new DuplicateKeyException("dup"));

    assertThatThrownBy(() -> repository.insert(1L, "fire", 2L))
        .isInstanceOf(DuplicateKeyException.class);

    verify(mapper, times(2)).insertIfAbsent(1L, "fire", 2L);
  }

  @Test
  @DisplayName("첫 시도가 성공하면 다시 시도하지 않는다")
  void insert_noRetryOnSuccess() {
    when(mapper.insertIfAbsent(anyLong(), anyString(), any())).thenReturn(1);

    assertThat(repository.insert(1L, "fire", 2L)).isEqualTo(1);

    verify(mapper, times(1)).insertIfAbsent(1L, "fire", 2L);
  }
}

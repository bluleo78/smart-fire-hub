package com.smartfirehub.securitylevel.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessGrantMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetAccessGrantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatisDatasetAccessGrantRepository.insertIfAbsent 의 DuplicateKeyException 중복 키 재시도 배선만
 * 고정한다(WD-711) — 재시도 규칙 자체는 PlatformRetriesTest 가 맡는다.
 */
class MyBatisDatasetAccessGrantRepositoryRetryTest {

  private final DatasetAccessGrantMapper mapper = mock(DatasetAccessGrantMapper.class);
  private final MyBatisDatasetAccessGrantRepository repository =
      new MyBatisDatasetAccessGrantRepository(mapper);

  @Test
  @DisplayName("첫 시도가 중복 키로 실패하면 한 번만 다시 시도하고, 이미 들어간 행을 만나 0 행이면 빈 값을 돌려준다")
  void insertIfAbsent_retriesOnceAfterDuplicateKey() {
    when(mapper.insertIfAbsent(anyLong(), any(), any(), any()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(0);

    assertThat(repository.insertIfAbsent(1L, 2L, null, 3L)).isEmpty();

    verify(mapper, times(2)).insertIfAbsent(1L, 2L, null, 3L);
  }

  @Test
  @DisplayName("첫 시도가 성공하면 다시 시도하지 않고 새 항목 id 를 돌려준다")
  void insertIfAbsent_noRetryOnSuccess() {
    when(mapper.insertIfAbsent(anyLong(), any(), any(), any())).thenReturn(1);
    when(mapper.selectIdBySubject(1L, 2L, null)).thenReturn(77L);

    assertThat(repository.insertIfAbsent(1L, 2L, null, 3L)).contains(77L);

    verify(mapper, times(1)).insertIfAbsent(1L, 2L, null, 3L);
  }

  @Test
  @DisplayName("copy — 첫 시도가 중복 키(동시 복사)면 한 번만 다시 돌아 두 번째 행 수를 돌려준다")
  void copy_retriesOnceAfterDuplicateKey() {
    when(mapper.copy(1L, 2L, 3L)).thenThrow(new DuplicateKeyException("dup")).thenReturn(0);

    assertThat(repository.copy(1L, 2L, 3L)).isZero();

    verify(mapper, times(2)).copy(1L, 2L, 3L);
  }

  @Test
  @DisplayName("insertIfAbsent — 대상 둘 다·둘 다 없음은 매퍼를 부르지 않고 무결성 예외(중복 키 아님 — 재시도 없음)")
  void insertIfAbsent_rejectsBothOrNeitherBeforeMapper() {
    assertThatThrownBy(() -> repository.insertIfAbsent(1L, 2L, 5L, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(DuplicateKeyException.class);
    assertThatThrownBy(() -> repository.insertIfAbsent(1L, null, null, null))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(mapper, never()).insertIfAbsent(anyLong(), any(), any(), any());
  }
}

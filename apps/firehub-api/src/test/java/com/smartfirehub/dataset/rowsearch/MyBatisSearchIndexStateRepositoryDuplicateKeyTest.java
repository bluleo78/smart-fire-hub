package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.mybatis.MyBatisSearchIndexStateRepository;
import com.smartfirehub.dataset.repository.mybatis.SearchIndexStateMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatisSearchIndexStateRepository.createIfAbsent 의 Tibero MERGE 중복 키 분기를 DB 없이 고정한다(WD-707). 중복
 * 키는 「이미 있음」이라 PG ON CONFLICT DO NOTHING 처럼 무시하고 재시도하지 않는다 — 다른 무결성 오류는 그대로 올라간다.
 */
class MyBatisSearchIndexStateRepositoryDuplicateKeyTest {

  private final SearchIndexStateMapper mapper = mock(SearchIndexStateMapper.class);
  private final MyBatisSearchIndexStateRepository repository =
      new MyBatisSearchIndexStateRepository(mapper);

  @Test
  @DisplayName("중복 키로 실패하면 예외 없이 끝나고 다시 시도하지 않는다(DO NOTHING 과 같음)")
  void createIfAbsent_swallowsDuplicateKeyWithoutRetry() {
    when(mapper.insertIfAbsent(anyLong())).thenThrow(new DuplicateKeyException("dup"));

    assertThatCode(() -> repository.createIfAbsent(7L)).doesNotThrowAnyException();

    verify(mapper, times(1)).insertIfAbsent(7L);
  }

  @Test
  @DisplayName("중복 키가 아닌 무결성 오류(예: 없는 데이터셋 FK)는 삼키지 않는다")
  void createIfAbsent_propagatesOtherIntegrityErrors() {
    when(mapper.insertIfAbsent(anyLong())).thenThrow(new DataIntegrityViolationException("fk"));

    assertThatThrownBy(() -> repository.createIfAbsent(7L))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(DuplicateKeyException.class);

    verify(mapper, times(1)).insertIfAbsent(7L);
  }

  @Test
  @DisplayName("성공하면 한 번만 부른다")
  void createIfAbsent_singleCallOnSuccess() {
    when(mapper.insertIfAbsent(anyLong())).thenReturn(1);

    repository.createIfAbsent(7L);

    verify(mapper, times(1)).insertIfAbsent(7L);
  }
}

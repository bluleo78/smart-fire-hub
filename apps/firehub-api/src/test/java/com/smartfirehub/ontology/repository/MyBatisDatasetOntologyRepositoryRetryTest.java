package com.smartfirehub.ontology.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.ontology.repository.mybatis.DatasetOntologyMapper;
import com.smartfirehub.ontology.repository.mybatis.MyBatisDatasetOntologyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatisDatasetOntologyRepository.bind 의 DuplicateKeyException 1회 재시도 분기를 DB 없이 고정한다(WD-711) —
 * Tibero MERGE 의 동시 첫 연결 패자(-10007) 대응.
 */
class MyBatisDatasetOntologyRepositoryRetryTest {

  private final DatasetOntologyMapper mapper = mock(DatasetOntologyMapper.class);
  private final GraphOntologySourceRepository source = mock(GraphOntologySourceRepository.class);
  private final MyBatisDatasetOntologyRepository repository =
      new MyBatisDatasetOntologyRepository(mapper, source);

  @Test
  @DisplayName("첫 시도가 중복 키로 실패하면 한 번만 다시 시도하고, 성공하면 출처를 한 번 기록한다")
  void bind_retriesOnceAfterDuplicateKey() {
    when(mapper.bind(anyLong(), anyLong(), any()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(1);

    repository.bind(10L, 20L, 30L);

    verify(mapper, times(2)).bind(10L, 20L, 30L);
    verify(source, times(1)).record(20L, 10L);
  }
}

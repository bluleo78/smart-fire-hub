package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.ontology.repository.mybatis.GraphOntologySourceMapper;
import com.smartfirehub.ontology.repository.mybatis.MyBatisGraphOntologySourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatisGraphOntologySourceRepository.record 의 DuplicateKeyException 1회 재시도 분기를 DB 없이 고정한다(WD-711)
 * — Tibero MERGE 의 동시 첫 기록 패자(-10007) 대응. 클래스 단위 트랜잭션(fail-open 방지)도 함께 확인한다.
 */
class MyBatisGraphOntologySourceRepositoryRetryTest {

  private final GraphOntologySourceMapper mapper = mock(GraphOntologySourceMapper.class);
  private final MyBatisGraphOntologySourceRepository repository =
      new MyBatisGraphOntologySourceRepository(mapper);

  @Test
  @DisplayName("첫 시도가 중복 키로 실패하면 한 번만 다시 시도한다")
  void record_retriesOnceAfterDuplicateKey() {
    when(mapper.record(anyLong(), anyLong()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(0);

    repository.record(20L, 10L);

    verify(mapper, times(2)).record(20L, 10L);
  }

  @Test
  @DisplayName("클래스 단위 @Transactional 이 있다 — 없으면 RLS 0행으로 existsUnreadableSource 가 fail-open 한다")
  void classIsTransactional() {
    assertThat(MyBatisGraphOntologySourceRepository.class.getAnnotation(Transactional.class))
        .isNotNull();
  }
}

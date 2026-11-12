package com.smartfirehub.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.mapping.repository.mybatis.MappingMapper;
import com.smartfirehub.mapping.repository.mybatis.MyBatisMappingRepository;
import com.smartfirehub.ontology.repository.GraphOntologySourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatisMappingRepository.upsert 의 DuplicateKeyException 1회 재시도 분기를 DB 없이 고정한다(WD-708) — Tibero
 * MERGE 의 동시 첫 저장 패자(-10007) 대응. 클래스 단위 트랜잭션(조회가 RLS 0행이 되는 것 방지)도 함께 확인한다.
 */
class MyBatisMappingRepositoryRetryTest {

  private final MappingMapper mapper = mock(MappingMapper.class);
  private final GraphOntologySourceRepository source = mock(GraphOntologySourceRepository.class);
  private final MyBatisMappingRepository repository = new MyBatisMappingRepository(mapper, source);

  @Test
  @DisplayName("첫 시도가 중복 키로 실패하면 한 번만 다시 시도하고 출처는 한 번만 남긴다")
  void upsert_retriesOnceAfterDuplicateKey() {
    when(mapper.upsert(anyLong(), anyLong(), any(), any(), any()))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(1);

    repository.upsert(7L, 3L, "{}", "draft", 5L);

    verify(mapper, times(2)).upsert(7L, 3L, "{}", "draft", 5L);
    verify(source, times(1)).record(3L, 7L);
  }

  @Test
  @DisplayName("클래스 단위 @Transactional 이 있다 — 없으면 트랜잭션 밖 조회가 RLS 로 0행이 된다")
  void classIsTransactional() {
    assertThat(MyBatisMappingRepository.class.getAnnotation(Transactional.class)).isNotNull();
  }
}

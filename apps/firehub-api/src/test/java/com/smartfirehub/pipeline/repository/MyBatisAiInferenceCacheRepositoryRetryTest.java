package com.smartfirehub.pipeline.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.pipeline.repository.AiInferenceCacheRepository.Entry;
import com.smartfirehub.pipeline.repository.mybatis.AiInferenceCacheMapper;
import com.smartfirehub.pipeline.repository.mybatis.MyBatisAiInferenceCacheRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatisAiInferenceCacheRepository.insertIgnoringConflicts 의 조각·중복 제거·폴백 배선만 고정한다(WD-708) — 재시도 규칙
 * 자체는 PlatformRetriesTest, 실제 DB 결과는 계약 테스트가 맡는다.
 */
class MyBatisAiInferenceCacheRepositoryRetryTest {

  @Test
  @DisplayName("다건 문장이 중복 키로 실패하면 그 조각만 한 건씩 넣고, 한 건이 다시 중복 키면 그 건만 한 번 더 시도한다")
  void insertIgnoringConflicts_fallsBackPerRowOnDuplicateKey() {
    AiInferenceCacheMapper mapper = mock(AiInferenceCacheMapper.class);
    when(mapper.insertIgnoringBatch(eq("v1"), anyList()))
        .thenThrow(new DuplicateKeyException("dup"));
    when(mapper.insertIgnoring("h1", "v1", "{}"))
        .thenThrow(new DuplicateKeyException("dup"))
        .thenReturn(0);
    when(mapper.insertIgnoring("h2", "v1", "{}")).thenReturn(1);

    new MyBatisAiInferenceCacheRepository(mapper)
        .insertIgnoringConflicts("v1", List.of(new Entry("h1", "{}"), new Entry("h2", "{}")));

    verify(mapper, times(1)).insertIgnoringBatch(eq("v1"), anyList());
    verify(mapper, times(2)).insertIgnoring("h1", "v1", "{}");
    verify(mapper, times(1)).insertIgnoring("h2", "v1", "{}");
  }

  @Test
  @DisplayName("결과 JSON 이 상한(1000 자)을 넘는 건은 다건 문장에 싣지 않고 한 건씩 넣는다")
  @SuppressWarnings("unchecked")
  void insertIgnoringConflicts_oversizedGoesPerRow() {
    AiInferenceCacheMapper mapper = mock(AiInferenceCacheMapper.class);
    String big = "{\"t\":\"" + "x".repeat(1_000) + "\"}";

    new MyBatisAiInferenceCacheRepository(mapper)
        .insertIgnoringConflicts("v1", List.of(new Entry("small", "{}"), new Entry("big", big)));

    verify(mapper, times(1)).insertIgnoring("big", "v1", big);
    ArgumentCaptor<List<Entry>> captor = ArgumentCaptor.forClass(List.class);
    verify(mapper, times(1)).insertIgnoringBatch(eq("v1"), captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue())
        .containsExactly(new Entry("small", "{}"));
  }

  @Test
  @DisplayName("해시 중복은 앞의 것만 남기고 500 건씩 나눠 다건 문장으로 넣는다 — 폴백은 쓰지 않는다")
  @SuppressWarnings("unchecked")
  void insertIgnoringConflicts_dedupesAndChunks() {
    AiInferenceCacheMapper mapper = mock(AiInferenceCacheMapper.class);
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      entries.add(new Entry("h" + i, "{\"i\":" + i + "}"));
    }
    entries.add(new Entry("h0", "{\"dup\":true}"));

    new MyBatisAiInferenceCacheRepository(mapper).insertIgnoringConflicts("v1", entries);

    ArgumentCaptor<List<Entry>> captor = ArgumentCaptor.forClass(List.class);
    verify(mapper, times(3)).insertIgnoringBatch(eq("v1"), captor.capture());
    List<List<Entry>> chunks = captor.getAllValues();
    org.assertj.core.api.Assertions.assertThat(chunks)
        .extracting(List::size)
        .containsExactly(500, 500, 1);
    org.assertj.core.api.Assertions.assertThat(chunks.get(0).get(0))
        .isEqualTo(new Entry("h0", "{\"i\":0}"));
    verify(mapper, never()).insertIgnoring(any(), any(), any());
  }
}

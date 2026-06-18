package com.smartfirehub.dataset.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** DatasetEmbeddingBackfillService 단위 테스트: 동기 source_text 선행 → 비동기 enqueue 순서 불변식 검증. */
@ExtendWith(MockitoExtension.class)
class DatasetEmbeddingBackfillServiceTest {

  @Mock DatasetMetaReader metaReader;
  @Mock DatasetEmbeddingService embeddingService;
  @Mock JobScheduler jobScheduler;

  @Test
  void backfillAll_모든_syncSourceText가_첫_enqueue_이전에_호출된다() {
    when(metaReader.findAllIds()).thenReturn(List.of(1L, 2L, 3L));

    DatasetEmbeddingBackfillService service =
        new DatasetEmbeddingBackfillService(metaReader, embeddingService, jobScheduler);
    int scheduled = service.backfillAll();

    // 처리 대상 수 반환 검증.
    assertThat(scheduled).isEqualTo(3);

    // 순서 불변식: 모든 id 의 syncSourceText(동기, 키워드 가시성)가 끝난 뒤에야 첫 enqueue(비동기)가 시작된다.
    InOrder inOrder = inOrder(embeddingService, jobScheduler);
    inOrder.verify(embeddingService).syncSourceText(1L);
    inOrder.verify(embeddingService).syncSourceText(2L);
    inOrder.verify(embeddingService).syncSourceText(3L);
    // 임베딩 재색인 enqueue(데이터셋별 3회)는 모두 위 syncSourceText 들 이후에 발생한다.
    inOrder.verify(jobScheduler, times(3)).enqueue(any(JobLambda.class));
  }

  @Test
  void backfillAll_데이터셋이_없으면_아무_작업도_하지_않고_0을_반환한다() {
    when(metaReader.findAllIds()).thenReturn(List.of());

    DatasetEmbeddingBackfillService service =
        new DatasetEmbeddingBackfillService(metaReader, embeddingService, jobScheduler);

    assertThat(service.backfillAll()).isZero();
    verify(jobScheduler, times(0)).enqueue(any(JobLambda.class));
  }
}

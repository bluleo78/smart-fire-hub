package com.smartfirehub.dataset.search;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;

/**
 * 관리자용 데이터셋 검색 인덱스 백필 오케스트레이터.
 *
 * <p>{@link DatasetEmbeddingService} 의 3-arg 단위테스트 시그니처를 보존하기 위해 백필(잡 스케줄러 의존) 로직은
 * 별도 서비스로 분리했다.
 *
 * <p>동작: (1) 모든 데이터셋 id 의 {@code syncSourceText} 를 먼저 동기 실행해 키워드 검색을 즉시 가능케 한 뒤,
 * (2) {@code reindexEmbedding} 을 데이터셋별 잡으로 enqueue 해 비용이 큰 임베딩 생성을 비동기 분산한다.
 * Jobrunr 는 빈 메서드 참조 람다를 직렬화해 백그라운드에서 해당 빈 메서드를 호출한다
 * (DocumentIngestionService.processIngestion 과 동일 메커니즘).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatasetEmbeddingBackfillService {

  private final DatasetMetaReader metaReader;
  private final DatasetEmbeddingService embeddingService;
  private final JobScheduler jobScheduler;

  /**
   * 전체 데이터셋 인덱스 백필을 시작한다.
   *
   * @return 처리 대상 데이터셋 수
   */
  public int backfillAll() {
    List<Long> ids = metaReader.findAllIds();

    // (1) source_text 를 전부 먼저 동기 적재 → 임베딩 생성 전에도 키워드 검색에 즉시 노출.
    for (Long id : ids) {
      embeddingService.syncSourceText(id);
    }

    // (2) 임베딩 재색인은 데이터셋별 잡으로 분산 enqueue(외부 호출 비용을 백그라운드로 이전).
    for (Long id : ids) {
      long datasetId = id; // 람다 캡처를 위한 final 지역 복사
      jobScheduler.enqueue(() -> embeddingService.reindexEmbedding(datasetId));
    }

    log.info("Dataset embedding backfill scheduled: count={}", ids.size());
    return ids.size();
  }
}

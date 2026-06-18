package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetTagRepository;
import com.smartfirehub.dataset.search.DatasetChangedEvent;
import com.smartfirehub.dataset.search.DatasetEmbeddingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatasetTagService {

  private final DatasetRepository datasetRepository;
  private final DatasetTagRepository tagRepository;
  // 검색 인덱싱: source_text 동기 저장 + 임베딩 비동기 재생성 트리거
  private final DatasetEmbeddingService datasetEmbeddingService;
  private final ApplicationEventPublisher events;

  @Transactional
  public void addTag(Long datasetId, String tagName, Long userId) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));

    boolean alreadyExists = tagRepository.findByDatasetId(datasetId).contains(tagName);
    if (alreadyExists) {
      throw new IllegalStateException("Tag already exists: " + tagName);
    }
    tagRepository.insert(datasetId, tagName, userId);

    // 태그 추가 반영
    reindexSearch(datasetId);
  }

  @Transactional
  public void deleteTag(Long datasetId, String tagName) {
    datasetRepository
        .findById(datasetId)
        .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    tagRepository.delete(datasetId, tagName);

    // 태그 삭제 반영
    reindexSearch(datasetId);
  }

  /** 태그 변경 직후 검색 인덱스 갱신(source_text 동기 + 임베딩 비동기). */
  private void reindexSearch(long datasetId) {
    datasetEmbeddingService.syncSourceText(datasetId); // 동기: 같은 트랜잭션, 키워드 검색 즉시 노출
    events.publishEvent(new DatasetChangedEvent(datasetId)); // 비동기: 커밋 후 임베딩
  }

  @Transactional(readOnly = true)
  public List<String> getAllDistinctTags() {
    return tagRepository.findAllDistinctTags();
  }
}

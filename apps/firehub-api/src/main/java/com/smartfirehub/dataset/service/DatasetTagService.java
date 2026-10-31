package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.exception.DatasetTagNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetTagRepository;
import com.smartfirehub.dataset.search.DatasetChangedEvent;
import com.smartfirehub.dataset.search.DatasetEmbeddingService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.List;
import java.util.Map;
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
  private final DatasetAccessGuard datasetAccessGuard;
  // 태그 추가·제거 감사 기록(WD-463)
  private final DatasetAuditRecorder datasetAuditRecorder;

  @Transactional
  public void addTag(Long datasetId, String tagName, Long userId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());

    // 태그 중복 검사는 대소문자를 구분하지 않는다 — "Test"와 "test"는 동일 태그로 취급 (#671)
    boolean alreadyExists =
        tagRepository.findByDatasetId(datasetId).stream()
            .anyMatch(existing -> existing.equalsIgnoreCase(tagName));
    if (alreadyExists) {
      throw new IllegalStateException("이미 추가된 태그입니다: " + tagName);
    }
    // 위 검사와 이 삽입 사이에 다른 요청이 대소문자만 다른 태그를 넣었으면 DB 유일 인덱스(V155)가 막아 0 이 온다 — 같은 409 로 알린다(WD-389)
    if (tagRepository.insert(datasetId, tagName, userId) == 0) {
      throw new IllegalStateException("이미 추가된 태그입니다: " + tagName);
    }

    // 태그 추가 반영
    reindexSearch(datasetId);

    // 감사(WD-463) — 중복 거부(409)를 지난 뒤에만. 태그 이름은 설명에 실어도 된다(값이 아닌 분류 이름).
    datasetAuditRecorder.record(
        userId,
        "UPDATE",
        datasetId,
        dataset.name(),
        "TAG_ADD",
        "태그 추가: " + dataset.name() + " — " + tagName,
        Map.of("tag", tagName));
  }

  /** 행위자 없는 내부·테스트 경로 — 감사는 행위자 "system" 으로 남는다. 사용자 요청은 userId 를 받는 오버로드로 들어온다. */
  @Transactional
  public void deleteTag(Long datasetId, String tagName) {
    deleteTag(datasetId, tagName, null);
  }

  /** 태그를 제거하고, 실제로 지웠으면 감사(WD-463, TAG_REMOVE)를 남긴다 — 없는 태그(404)는 남기지 않는다. */
  @Transactional
  public void deleteTag(Long datasetId, String tagName, Long userId) {
    DatasetResponse dataset =
        datasetRepository.findById(datasetId).orElseThrow(() -> new DatasetNotFoundException());
    // 추가와 같이 대소문자를 무시해 지우고, 지운 것이 없으면 404 로 알린다 — 204 로 지운 척하지 않는다 (WD-242)
    int deleted = tagRepository.deleteIgnoreCase(datasetId, tagName);
    if (deleted == 0) {
      throw new DatasetTagNotFoundException();
    }

    // 태그 삭제 반영
    reindexSearch(datasetId);

    // 감사(WD-463) — 태그는 데이터셋 메타 편집이라 데이터가 사라지는 삭제(DELETE)가 아닌 UPDATE 로 남긴다.
    datasetAuditRecorder.record(
        userId,
        "UPDATE",
        datasetId,
        dataset.name(),
        "TAG_REMOVE",
        "태그 제거: " + dataset.name() + " — " + tagName,
        Map.of("tag", tagName));
  }

  /** 태그 변경 직후 검색 인덱스 갱신(source_text 동기 + 임베딩 비동기). */
  private void reindexSearch(long datasetId) {
    datasetEmbeddingService.syncSourceText(datasetId); // 동기: 같은 트랜잭션, 키워드 검색 즉시 노출
    events.publishEvent(new DatasetChangedEvent(datasetId)); // 비동기: 커밋 후 임베딩
  }

  @Transactional(readOnly = true)
  public List<String> getAllDistinctTags() {
    // 숨김 데이터셋의 태그명은 집계에서 제외한다.
    return tagRepository.findAllDistinctTags(datasetAccessGuard.visibleCondition());
  }
}

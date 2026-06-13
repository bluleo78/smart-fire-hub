package com.smartfirehub.document.service;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.dto.SearchMode;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 문서 검색: mode 에 따라 벡터(SEMANTIC)·트라이그램(KEYWORD)·RRF 융합(HYBRID, 기본)으로 분기한다.
 * HYBRID 는 두 검색의 후보 풀을 가져와 RRF(Reciprocal Rank Fusion)로 순위를 융합한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentSearchService {

  private final EmbeddingProviderFactory embeddingProviderFactory;
  private final DocumentChunkRepository chunkRepository;

  // RRF 상수: 후보 풀 크기와 융합 상수 k. k=60 은 RRF 표준 권장값.
  private static final int CANDIDATE_POOL = 50;
  private static final int RRF_K = 60;

  public List<DocumentSearchHit> search(DocumentSearchRequest request) {
    if (request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("검색어가 비어 있습니다");
    }
    return switch (request.mode()) {
      case KEYWORD -> chunkRepository.searchByTrigram(
          request.query(), request.datasetIds(), request.topK());
      case SEMANTIC -> chunkRepository.searchByCosine(
          embedQuery(request.query()), request.datasetIds(), request.topK());
      case HYBRID -> hybridSearch(request);
    };
  }

  /** 쿼리 1건 임베딩 — 인제스션과 동일 provider 라야 비교가 유효하다. */
  private float[] embedQuery(String query) {
    EmbeddingProvider provider = embeddingProviderFactory.current();
    return provider.embed(List.of(query)).get(0);
  }

  /** 시맨틱·키워드 후보 풀을 RRF 로 융합해 상위 topK 를 반환한다. */
  private List<DocumentSearchHit> hybridSearch(DocumentSearchRequest request) {
    List<DocumentSearchHit> semantic = chunkRepository.searchByCosine(
        embedQuery(request.query()), request.datasetIds(), CANDIDATE_POOL);
    List<DocumentSearchHit> keyword = chunkRepository.searchByTrigram(
        request.query(), request.datasetIds(), CANDIDATE_POOL);
    return rrfFuse(List.of(semantic, keyword), request.topK());
  }

  /**
   * RRF 융합: 각 랭킹 리스트에서 chunkId 의 rank(0-based)로 1/(RRF_K + rank + 1)을 누적한다.
   * 누적 점수 내림차순으로 정렬해 상위 limit 개를 반환한다. hit.score 는 RRF 점수로 대체된다.
   * 동점은 chunkId 오름차순으로 안정 정렬(결정성 보장).
   */
  static List<DocumentSearchHit> rrfFuse(List<List<DocumentSearchHit>> rankings, int limit) {
    Map<Long, Double> scoreByChunk = new LinkedHashMap<>();
    Map<Long, DocumentSearchHit> hitByChunk = new LinkedHashMap<>();
    for (List<DocumentSearchHit> ranking : rankings) {
      for (int rank = 0; rank < ranking.size(); rank++) {
        DocumentSearchHit hit = ranking.get(rank);
        double contribution = 1.0 / (RRF_K + rank + 1);
        scoreByChunk.merge(hit.chunkId(), contribution, Double::sum);
        hitByChunk.putIfAbsent(hit.chunkId(), hit);
      }
    }
    List<Map.Entry<Long, Double>> entries = new ArrayList<>(scoreByChunk.entrySet());
    entries.sort((x, y) -> {
      int byScore = Double.compare(y.getValue(), x.getValue()); // 점수 내림차순
      return byScore != 0 ? byScore : Long.compare(x.getKey(), y.getKey()); // 동점 → chunkId 오름차순
    });
    List<DocumentSearchHit> result = new ArrayList<>();
    for (Map.Entry<Long, Double> e : entries) {
      if (result.size() >= limit) break;
      DocumentSearchHit base = hitByChunk.get(e.getKey());
      result.add(new DocumentSearchHit(
          base.chunkId(), base.documentFileId(), base.datasetId(),
          base.fileName(), base.chunkIndex(), base.content(), e.getValue()));
    }
    return result;
  }
}

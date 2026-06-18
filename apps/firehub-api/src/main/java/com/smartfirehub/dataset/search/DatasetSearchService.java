package com.smartfirehub.dataset.search;

import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 데이터셋 카탈로그 검색 서비스 (DocumentSearchService 복제).
 *
 * <p>mode 에 따라 벡터(SEMANTIC)·트라이그램(KEYWORD)·RRF 융합(HYBRID, 기본)으로 분기한다. HYBRID 는 두 검색의 후보 풀을
 * 가져와 RRF(Reciprocal Rank Fusion)로 순위를 융합한다. RRF 상수·점수 공식은 DocumentSearchService 와 동일하게 맞춘다.
 */
@Service
@RequiredArgsConstructor
public class DatasetSearchService {

  // RRF 상수: 후보 풀 크기와 융합 상수 k. DocumentSearchService 와 동일(k=60 은 RRF 표준 권장값).
  private static final int CANDIDATE_POOL = 50;
  private static final int RRF_K = 60;
  // topK 정규화 경계: 미지정 시 기본 10, 최대 20.
  private static final int DEFAULT_TOP_K = 10;
  private static final int MAX_TOP_K = 20;

  private final DatasetSearchRepository repository;
  private final EmbeddingProviderFactory embeddingFactory;

  /** mode 분기 진입점. mode null → HYBRID, topK 정규화 후 각 검색을 수행한다. */
  public List<DatasetSearchHit> search(DatasetSearchRequest req) {
    if (req.query() == null || req.query().isBlank()) {
      throw new IllegalArgumentException("검색어가 비어 있습니다");
    }
    DatasetSearchMode mode = req.mode() == null ? DatasetSearchMode.HYBRID : req.mode();
    int topK = clampTopK(req.topK());
    String storageType = req.storageType();
    return switch (mode) {
      case KEYWORD -> repository.searchByTrigram(req.query(), storageType, topK);
      case SEMANTIC -> repository.searchByCosine(embed(req.query()), storageType, topK);
      case HYBRID -> hybrid(req.query(), storageType, topK);
    };
  }

  /** 시맨틱·키워드 후보 풀(CANDIDATE_POOL)을 RRF 로 융합해 상위 topK 를 반환한다. */
  private List<DatasetSearchHit> hybrid(String query, String storageType, int topK) {
    List<DatasetSearchHit> semantic =
        repository.searchByCosine(embed(query), storageType, CANDIDATE_POOL);
    List<DatasetSearchHit> keyword =
        repository.searchByTrigram(query, storageType, CANDIDATE_POOL);
    return rrfFuse(List.of(semantic, keyword), topK);
  }

  /**
   * RRF 융합: 각 랭킹 리스트에서 datasetId 의 rank(0-based)로 1/(RRF_K + rank + 1)을 누적한다. 누적 점수 내림차순으로
   * 정렬해 상위 limit 개를 반환한다. hit.score 는 RRF 점수로 대체된다. 동점은 datasetId 오름차순으로 안정 정렬(결정성 보장).
   */
  static List<DatasetSearchHit> rrfFuse(List<List<DatasetSearchHit>> rankings, int limit) {
    Map<Long, Double> scoreById = new LinkedHashMap<>();
    Map<Long, DatasetSearchHit> hitById = new LinkedHashMap<>();
    for (List<DatasetSearchHit> ranking : rankings) {
      for (int rank = 0; rank < ranking.size(); rank++) {
        DatasetSearchHit hit = ranking.get(rank);
        double contribution = 1.0 / (RRF_K + rank + 1);
        scoreById.merge(hit.datasetId(), contribution, Double::sum);
        hitById.putIfAbsent(hit.datasetId(), hit);
      }
    }
    List<Map.Entry<Long, Double>> entries = new ArrayList<>(scoreById.entrySet());
    entries.sort(
        (x, y) -> {
          int byScore = Double.compare(y.getValue(), x.getValue()); // 점수 내림차순
          return byScore != 0 ? byScore : Long.compare(x.getKey(), y.getKey()); // 동점 → datasetId 오름차순
        });
    List<DatasetSearchHit> result = new ArrayList<>();
    for (Map.Entry<Long, Double> e : entries) {
      if (result.size() >= limit) break;
      DatasetSearchHit base = hitById.get(e.getKey());
      result.add(
          new DatasetSearchHit(
              base.datasetId(),
              base.name(),
              base.description(),
              base.storageType(),
              base.originType(),
              base.tableName(),
              base.category(),
              e.getValue()));
    }
    return result;
  }

  /** 쿼리 1건 임베딩 — 인제스션과 동일 provider 라야 비교가 유효하다. */
  private float[] embed(String query) {
    EmbeddingProvider provider = embeddingFactory.current();
    return provider.embed(List.of(query)).get(0);
  }

  /** topK 정규화: null → 기본 10, 1 미만 → 1, 20 초과 → 20. */
  private int clampTopK(Integer topK) {
    if (topK == null) return DEFAULT_TOP_K;
    return Math.max(1, Math.min(topK, MAX_TOP_K));
  }
}

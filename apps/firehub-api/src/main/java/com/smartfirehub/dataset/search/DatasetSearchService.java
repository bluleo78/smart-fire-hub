package com.smartfirehub.dataset.search;

import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.util.RankFusion;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.vectorstore.DatasetVectorIndex;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import com.smartfirehub.vectorstore.VectorStoreUnavailableException;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데이터셋 카탈로그 검색 서비스 (DocumentSearchService 복제).
 *
 * <p>mode 에 따라 벡터(SEMANTIC)·트라이그램(KEYWORD)·RRF 융합(HYBRID, 기본)으로 분기한다. HYBRID 는 두 검색의 후보 풀을 가져와
 * RRF(Reciprocal Rank Fusion)로 순위를 융합한다. RRF 상수·점수 공식은 DocumentSearchService 와 동일하게 맞춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetSearchService {

  // RRF 상수: 후보 풀 크기. 융합 상수 k(=60)는 RankFusion 공통 유틸에서 관리한다.
  private static final int CANDIDATE_POOL = 50;
  // topK 정규화 경계: 미지정 시 기본 10, 최대 20.
  private static final int DEFAULT_TOP_K = 10;
  private static final int MAX_TOP_K = 20;

  private final DatasetVectorIndex repository; // 벡터·키워드 색인 포트(ADR 0002 결정 2)
  private final EmbeddingProviderFactory embeddingFactory;
  private final DatasetAccessGuard datasetAccessGuard;
  // 플랫폼 DB 가 벡터·트라이그램 검색을 지원하는지(꺼진 단계는 빈 목록으로 취급 — 5xx 를 내지 않는다).
  private final VectorStoreFeatures features;

  /** mode 분기 진입점. mode null → HYBRID, topK 정규화 후 각 검색을 수행한다. */
  // RLS 가 걸린 dataset_embedding 을 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public List<DatasetSearchHit> search(DatasetSearchRequest req, Clearance clearance) {
    if (req.query() == null || req.query().isBlank()) {
      throw new IllegalArgumentException("검색어가 비어 있습니다");
    }
    DatasetSearchMode mode = req.mode() == null ? DatasetSearchMode.HYBRID : req.mode();
    int topK = clampTopK(req.topK());
    String storageType = req.storageType();
    // 가시성은 여기서 한 번 캡처해(AI 문맥 고정 — 예전 visibleSql 렌더 시점과 같다) KEYWORD/SEMANTIC/HYBRID 모든 분기가 공유한다.
    // SQL 렌더(별칭 d)는 리포지토리 구현이 맡는다(WD-485 T10).
    DatasetVisibility visibility = datasetAccessGuard.visibility(clearance);
    return switch (mode) {
      case KEYWORD -> keyword(req.query(), storageType, topK, visibility);
      case SEMANTIC -> semantic(req.query(), storageType, topK, visibility);
      case HYBRID -> hybrid(req.query(), storageType, topK, visibility);
    };
  }

  /** 시맨틱·키워드 후보 풀(CANDIDATE_POOL)을 RRF 로 융합해 상위 topK 를 반환한다. */
  private List<DatasetSearchHit> hybrid(
      String query, String storageType, int topK, DatasetVisibility visibility) {
    // 꺼진 단계는 빈 목록 — 융합은 남은 단계만으로 이뤄진다(둘 다 꺼지면 빈 결과).
    List<DatasetSearchHit> semantic = semantic(query, storageType, CANDIDATE_POOL, visibility);
    List<DatasetSearchHit> keyword = keyword(query, storageType, CANDIDATE_POOL, visibility);
    return rrfFuse(List.of(semantic, keyword), topK);
  }

  /** 벡터(코사인) 단계. 플랫폼 DB 가 지원하지 않으면 질의 임베딩 호출도 없이 빈 목록. */
  private List<DatasetSearchHit> semantic(
      String query, String storageType, int limit, DatasetVisibility visibility) {
    if (!features.supports(PlatformDbFeature.VECTOR_SEARCH)) {
      return List.of();
    }
    if (!features.available()) {
      return List.of(); // 저장소 일시 중단 — 질의 임베딩 호출도 아낀다(WD-724)
    }
    return unlessStoreDown(
        "의미",
        () -> {
          QueryVector q = embed(query);
          return repository.semantic(q.space(), q.vector(), storageType, limit, visibility);
        });
  }

  /** 트라이그램 단계. 플랫폼 DB 가 지원하지 않으면 호출하지 않고 빈 목록. */
  private List<DatasetSearchHit> keyword(
      String query, String storageType, int limit, DatasetVisibility visibility) {
    if (!features.supports(PlatformDbFeature.TRIGRAM_SEARCH)) {
      return List.of();
    }
    if (!features.available()) {
      return List.of();
    }
    return unlessStoreDown("키워드", () -> repository.keyword(query, storageType, limit, visibility));
  }

  /**
   * 검색 단계 하나를 부르고, 그사이 벡터 저장소가 내려가 실패하면 그 단계만 빈 목록으로 돌린다(ADR 0002 결정 11, WD-724). 가용성 캐시(몇 초)가 아직
   * 「켜짐」일 때 내려간 경우다. 5xx 대신 빈 결과를 주고, 화면·에이전트는 기능 응답의 {@code vectorStoreAvailable} 로 안내한다.
   */
  private static List<DatasetSearchHit> unlessStoreDown(
      String stage, Supplier<List<DatasetSearchHit>> call) {
    try {
      return call.get();
    } catch (VectorStoreUnavailableException e) {
      log.warn("벡터 저장소 장애로 {} 검색 단계를 건너뜀: {}", stage, e.getMessage());
      return List.of();
    }
  }

  /** RRF 융합: {@link RankFusion} 위임. hit.score 는 RRF 점수로 대체된다. */
  static List<DatasetSearchHit> rrfFuse(List<List<DatasetSearchHit>> rankings, int limit) {
    return RankFusion.fuse(rankings, DatasetSearchHit::datasetId, limit).stream()
        .map(
            f ->
                new DatasetSearchHit(
                    f.hit().datasetId(),
                    f.hit().name(),
                    f.hit().description(),
                    f.hit().storageType(),
                    f.hit().originType(),
                    f.hit().tableName(),
                    f.hit().category(),
                    f.score()))
        .toList();
  }

  /** 질의 임베딩 + 그 공간(검색할 차원 테이블·모델 필터). */
  private record QueryVector(EmbeddingSpace space, float[] vector) {}

  /** 쿼리 1건 임베딩 — 인제스션과 동일 provider 라야 비교가 유효하다. */
  private QueryVector embed(String query) {
    EmbeddingProvider provider = embeddingFactory.current();
    return new QueryVector(EmbeddingSpace.of(provider), provider.embed(List.of(query)).get(0));
  }

  /** topK 정규화: null → 기본 10, 1 미만 → 1, 20 초과 → 20. */
  private int clampTopK(Integer topK) {
    if (topK == null) return DEFAULT_TOP_K;
    return Math.max(1, Math.min(topK, MAX_TOP_K));
  }
}

package com.smartfirehub.document.service;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.PlatformDbFeatures;
import com.smartfirehub.global.util.RankFusion;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서 검색: mode 에 따라 벡터(SEMANTIC)·트라이그램(KEYWORD)·RRF 융합(HYBRID, 기본)으로 분기한다. HYBRID 는 두 검색의 후보 풀을 가져와
 * RRF(Reciprocal Rank Fusion)로 순위를 융합한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentSearchService {

  private final EmbeddingProviderFactory embeddingProviderFactory;
  private final DocumentChunkRepository chunkRepository;
  private final DatasetAccessGuard datasetAccessGuard;
  // 플랫폼 DB 가 벡터·트라이그램 검색을 지원하는지(꺼진 단계는 빈 목록으로 취급 — 5xx 를 내지 않는다).
  private final PlatformDbFeatures features;

  // RRF 상수: 후보 풀 크기. 융합 상수 k(=60)는 RankFusion 공통 유틸에서 관리한다.
  private static final int CANDIDATE_POOL = 50;

  // RLS 가 걸린 document_chunk 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public List<DocumentSearchHit> search(DocumentSearchRequest request, Clearance clearance) {
    if (request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("검색어가 비어 있습니다");
    }
    // 전역 청크 검색이라 청크 본문이 그대로 나간다 — 볼 수 없는 데이터셋의 청크는 SQL 에서 제외한다. 가시성은 여기서 캡처하고(AI 문맥
    // 고정 — 예전 visibleSql 렌더 시점과 같다) SQL 렌더(별칭 d)는 리포지토리 구현이 맡는다(WD-485 T10).
    DatasetVisibility visibility = datasetAccessGuard.visibility(clearance);
    List<DocumentSearchHit> hits =
        switch (request.mode()) {
          case KEYWORD -> keyword(request, request.topK(), visibility);
          case SEMANTIC -> semantic(request, request.topK(), visibility);
          case HYBRID -> hybridSearch(request, visibility);
        };
    // 청크 본문이 호출자(AI)에게 간다 — 스마트 작업 실행 중이면 실린 청크의 데이터셋 등급을 리포트 등급에 넣는다(WD-198).
    datasetAccessGuard.noteReportReads(hits.stream().map(DocumentSearchHit::datasetId).toList());
    return hits;
  }

  /** 질의 임베딩 + 그 공간. 공간이 검색할 차원 테이블·모델 필터를 정한다(인제스션과 같은 provider). */
  private record QueryVector(EmbeddingSpace space, float[] vector) {}

  /** 쿼리 1건 임베딩 — 인제스션과 동일 provider 라야 비교가 유효하다. */
  private QueryVector embedQuery(String query) {
    EmbeddingProvider provider = embeddingProviderFactory.current();
    return new QueryVector(EmbeddingSpace.of(provider), provider.embed(List.of(query)).get(0));
  }

  /** 시맨틱·키워드 후보 풀을 RRF 로 융합해 상위 topK 를 반환한다. */
  private List<DocumentSearchHit> hybridSearch(
      DocumentSearchRequest request, DatasetVisibility visibility) {
    // 꺼진 단계는 빈 목록 — 융합은 남은 단계만으로 이뤄진다(둘 다 꺼지면 빈 결과).
    List<DocumentSearchHit> semantic = semantic(request, CANDIDATE_POOL, visibility);
    List<DocumentSearchHit> keyword = keyword(request, CANDIDATE_POOL, visibility);
    return rrfFuse(List.of(semantic, keyword), request.topK());
  }

  /** 벡터(코사인) 단계. 플랫폼 DB 가 지원하지 않으면 질의 임베딩 호출도 없이 빈 목록. */
  private List<DocumentSearchHit> semantic(
      DocumentSearchRequest request, int limit, DatasetVisibility visibility) {
    if (!features.supports(PlatformDbFeature.VECTOR_SEARCH)) {
      return List.of();
    }
    QueryVector q = embedQuery(request.query());
    return chunkRepository.searchByCosine(
        q.space(), q.vector(), request.datasetIds(), limit, visibility);
  }

  /** 트라이그램 단계. 플랫폼 DB 가 지원하지 않으면 호출하지 않고 빈 목록. */
  private List<DocumentSearchHit> keyword(
      DocumentSearchRequest request, int limit, DatasetVisibility visibility) {
    if (!features.supports(PlatformDbFeature.TRIGRAM_SEARCH)) {
      return List.of();
    }
    return chunkRepository.searchByTrigram(
        request.query(), request.datasetIds(), limit, visibility);
  }

  /** RRF 융합: {@link RankFusion} 위임. hit.score 는 RRF 점수로 대체된다. */
  static List<DocumentSearchHit> rrfFuse(List<List<DocumentSearchHit>> rankings, int limit) {
    return RankFusion.fuse(rankings, DocumentSearchHit::chunkId, limit).stream()
        .map(
            f ->
                new DocumentSearchHit(
                    f.hit().chunkId(),
                    f.hit().documentFileId(),
                    f.hit().datasetId(),
                    f.hit().fileName(),
                    f.hit().chunkIndex(),
                    f.hit().content(),
                    f.score()))
        .toList();
  }
}

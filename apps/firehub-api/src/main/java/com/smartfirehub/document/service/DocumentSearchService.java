package com.smartfirehub.document.service;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.dto.SearchMode;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.util.RankFusion;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서 검색: mode 에 따라 벡터(SEMANTIC)·트라이그램(KEYWORD)·RRF 융합(HYBRID, 기본)으로 분기한다.
 * HYBRID 는 두 검색의 후보 풀을 가져와 RRF(Reciprocal Rank Fusion)로 순위를 융합한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentSearchService {

  private final EmbeddingProviderFactory embeddingProviderFactory;
  private final DocumentChunkRepository chunkRepository;

  // RRF 상수: 후보 풀 크기. 융합 상수 k(=60)는 RankFusion 공통 유틸에서 관리한다.
  private static final int CANDIDATE_POOL = 50;

  // RLS 가 걸린 document_chunk 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
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

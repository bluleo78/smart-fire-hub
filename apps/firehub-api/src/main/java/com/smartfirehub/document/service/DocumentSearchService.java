package com.smartfirehub.document.service;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 문서 의미검색: 쿼리를 임베딩해 document_chunk 코사인 top-K 를 조회한다. */
@Service
@RequiredArgsConstructor
public class DocumentSearchService {

  private final EmbeddingProviderFactory embeddingProviderFactory;
  private final DocumentChunkRepository chunkRepository;

  public List<DocumentSearchHit> search(DocumentSearchRequest request) {
    if (request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("검색어가 비어 있습니다");
    }
    EmbeddingProvider provider = embeddingProviderFactory.current();
    // 쿼리 1건 임베딩 — 인제스션과 동일 provider 라야 비교가 유효하다.
    float[] queryEmbedding = provider.embed(List.of(request.query())).get(0);
    return chunkRepository.searchByCosine(queryEmbedding, request.datasetIds(), request.topK());
  }
}

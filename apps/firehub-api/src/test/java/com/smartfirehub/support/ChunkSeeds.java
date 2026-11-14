package com.smartfirehub.support;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingSpace;
import java.util.List;

/**
 * 시험용 청크 시드 — 청크 본문과 벡터를 함께 넣는다(WD-720).
 *
 * <p>운영 적재는 "청크 저장 → 커밋 → 벡터 쓰기" 로 나뉘어 예전의 {@code insertBatch} 가 없어졌다. 시험은 둘을 이어 부르면 같은 상태가 된다.
 */
public final class ChunkSeeds {

  private ChunkSeeds() {}

  /** 청크를 넣고({@code insertChunks}) 그 id 에 벡터를 쓴다({@code upsertEmbeddings}). 생성 id 를 청크 순서대로 돌려준다. */
  public static List<Long> insertWithVectors(
      DocumentChunkRepository repo,
      Long documentFileId,
      Long datasetId,
      List<Chunk> chunks,
      List<float[]> embeddings,
      EmbeddingSpace space) {
    List<Long> ids = repo.insertChunks(documentFileId, datasetId, chunks);
    repo.upsertEmbeddings(space, ids, embeddings);
    return ids;
  }
}

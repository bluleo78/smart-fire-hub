package com.smartfirehub.document.repository.mybatis;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.global.platformdb.mybatis.GeneratedId;

/** document_chunk MyBatis 매퍼의 입력 행(WD-720). */
final class DocumentChunkRows {

  private DocumentChunkRows() {}

  /**
   * 청크 한 건 INSERT 의 입력. 생성 키(id)를 돌려받아야 해서 {@link GeneratedId} 를 잇는다 — Tibero 에는 RETURNING 이 없다.
   */
  public static class ChunkInsert extends GeneratedId {
    private final Long documentFileId;
    private final Long datasetId;
    private final Chunk chunk;
    private final String contentHash;

    /**
     * @param contentHash 본문 해시({@code SourceHash.of(chunk.content())}) — 키워드 표시 판정용
     */
    public ChunkInsert(Long documentFileId, Long datasetId, Chunk chunk, String contentHash) {
      this.documentFileId = documentFileId;
      this.datasetId = datasetId;
      this.chunk = chunk;
      this.contentHash = contentHash;
    }

    public Long getDocumentFileId() {
      return documentFileId;
    }

    public Long getDatasetId() {
      return datasetId;
    }

    public Chunk getChunk() {
      return chunk;
    }

    public String getContentHash() {
      return contentHash;
    }
  }
}

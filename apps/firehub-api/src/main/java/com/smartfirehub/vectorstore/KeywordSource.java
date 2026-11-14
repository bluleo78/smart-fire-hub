package com.smartfirehub.vectorstore;

/**
 * 키워드 색인에 쓸 원문 하나(WD-720). 데이터셋 카탈로그는 {@code dataset_embedding.source_text}, 문서 청크는 청크 본문이다.
 *
 * <p>별도 저장소(Qdrant)는 원문에서 키워드 sparse 벡터를 만들어 저장한다(원문 자체는 저장하지 않는다 — ADR 0002 결정 5). pgvector 는 원문 열
 * 자체가 키워드 색인이라 이 값을 쓰지 않는다.
 *
 * @param id 데이터셋 id 또는 청크 id
 * @param datasetId 그 원문이 속한 데이터셋(카탈로그면 {@code id} 와 같다)
 * @param documentFileId 문서 청크면 그 문서 파일, 카탈로그면 null
 * @param text 원문
 */
public record KeywordSource(long id, long datasetId, Long documentFileId, String text) {

  /** 데이터셋 카탈로그 원문. */
  public static KeywordSource dataset(long datasetId, String text) {
    return new KeywordSource(datasetId, datasetId, null, text);
  }

  /** 문서 청크 본문. */
  public static KeywordSource chunk(ChunkRef ref, String text) {
    return new KeywordSource(ref.chunkId(), ref.datasetId(), ref.documentFileId(), text);
  }
}

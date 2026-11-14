package com.smartfirehub.vectorstore;

/**
 * 색인에 쓸 청크 하나의 위치(WD-720). 청크 id 와 함께 그 청크가 속한 데이터셋·문서 파일을 싣는다.
 *
 * <p>pgvector 구현은 청크 id 만 쓰고 나머지는 부모 행에서 다시 읽는다. 별도 저장소(Qdrant)는 플랫폼 DB 와 조인할 수 없어 데이터셋·문서 id 를 포인트
 * payload 로 함께 저장해야 한다(데이터셋 단위 dense 정리, 문서 삭제 필터). 그래서 포트가 처음부터 셋을 함께 받는다. 값은 플랫폼 리포지토리가 청크 행에서 읽어
 * 채운다 — 부모가 사라진 청크(삭제 경합)는 그 단계에서 빠진다.
 *
 * @param chunkId 청크 id({@code document_chunk.id})
 * @param datasetId 청크가 속한 데이터셋
 * @param documentFileId 청크가 속한 문서 파일
 */
public record ChunkRef(long chunkId, long datasetId, long documentFileId) {}

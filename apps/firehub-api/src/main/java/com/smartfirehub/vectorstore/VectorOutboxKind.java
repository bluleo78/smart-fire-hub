package com.smartfirehub.vectorstore;

/**
 * 벡터 아웃박스 행이 무엇을 지우는가(WD-721, ADR 0002 결정 6). 열 {@code vector_outbox.kind} 에 이름 그대로 저장한다.
 *
 * <p>어느 경우든 워커는 "행의 순번보다 작은 순번의 점" 만 지운다. 그래서 삭제를 쌓은 뒤 다시 쓴 벡터는 남는다.
 */
public enum VectorOutboxKind {
  /** 데이터셋 dense 만(키워드는 남김). 대상이 없으면 테넌트 전체. {@code keep_dim} 차원은 건드리지 않는다. */
  DATASET_DENSE,
  /** 데이터셋 색인 전부(dense 모든 차원 + 키워드). 데이터셋 삭제. */
  DATASET_ALL,
  /** 청크 dense 만(키워드는 남김). 대상은 데이터셋 id, 없으면 테넌트 전체. {@code keep_dim} 차원은 건드리지 않는다. */
  CHUNK_DENSE,
  /** 데이터셋의 청크 색인 전부(dense + 키워드). 데이터셋 삭제. */
  CHUNK_ALL_OF_DATASET,
  /** 문서 파일의 청크 색인 전부(dense + 키워드). 문서 삭제·재적재. 대상은 문서 파일 id. */
  CHUNK_ALL_OF_DOCUMENT
}

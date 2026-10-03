package com.smartfirehub.embedding;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 지원하는 임베딩 차원과 차원별 벡터 테이블 이름의 단일 출처(#713).
 *
 * <p><b>SQL 식별자(테이블명)는 이 열거형에서만 나온다.</b> 설정값·요청값을 테이블명에 이어 붙이면 SQL 인젝션 표면이 생기므로, 리포지토리는 항상 {@link
 * #chunkTable()}/{@link #datasetTable()} 을 쓴다. 차원을 늘리려면 상수 하나와 차원 테이블 2개를 만드는 마이그레이션을 함께 추가한다(HNSW
 * 상한 2000).
 */
public enum EmbeddingDimension {
  D1024(1024),
  D1536(1536);

  private final int size;

  EmbeddingDimension(int size) {
    this.size = size;
  }

  /** 벡터 차원 수. */
  public int size() {
    return size;
  }

  /** 문서 청크 벡터 테이블 이름. */
  public String chunkTable() {
    return "document_chunk_vec_" + size;
  }

  /** 데이터셋 카탈로그 벡터 테이블 이름. */
  public String datasetTable() {
    return "dataset_embedding_vec_" + size;
  }

  /**
   * 측정된 차원을 열거형으로 바꾼다. 미지원이면 {@link IllegalArgumentException} — 전역 핸들러가 400 으로 번역한다(설정 저장 시 "지원하지
   * 않는 차원" 안내).
   */
  public static EmbeddingDimension of(int size) {
    for (EmbeddingDimension d : values()) {
      if (d.size == size) return d;
    }
    throw new IllegalArgumentException("지원하지 않는 차원 " + size + " (지원: " + supportedList() + ")");
  }

  /** 안내 문구용 지원 차원 목록("1024, 1536"). */
  public static String supportedList() {
    return Arrays.stream(values())
        .map(d -> String.valueOf(d.size))
        .collect(Collectors.joining(", "));
  }
}

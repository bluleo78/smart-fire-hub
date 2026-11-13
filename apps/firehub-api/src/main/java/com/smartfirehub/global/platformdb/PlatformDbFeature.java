package com.smartfirehub.global.platformdb;

/**
 * 플랫폼 DB 구현체마다 지원 여부가 갈리는 선택 기능 목록.
 *
 * <p>왜 필요한가: 벡터·트라이그램 검색은 PostgreSQL 확장(pgvector·pg_trgm)에 기대고 있어 다른 플랫폼 DB(Tibero 등)에는 없을 수 있다.
 * 서비스가 구현체를 알지 않고 {@link com.smartfirehub.vectorstore.VectorStoreFeatures#supports} 로만 물어 꺼진 기능의
 * 호출을 건너뛰게 한다.
 */
public enum PlatformDbFeature {
  /** 벡터 유사도(의미) 검색 — 벡터 테이블 쓰기·HNSW 조회·벡터 정리 일체. */
  VECTOR_SEARCH,
  /** 트라이그램 유사도(키워드) 검색. */
  TRIGRAM_SEARCH
}

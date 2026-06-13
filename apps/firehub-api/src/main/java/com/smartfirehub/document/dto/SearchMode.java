package com.smartfirehub.document.dto;

/** 문서 검색 모드. SEMANTIC=벡터 코사인, KEYWORD=pg_trgm 트라이그램, HYBRID=RRF 융합(기본). */
public enum SearchMode {
  SEMANTIC,
  KEYWORD,
  HYBRID
}

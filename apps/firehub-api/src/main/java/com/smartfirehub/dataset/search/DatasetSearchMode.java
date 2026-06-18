package com.smartfirehub.dataset.search;

/** 데이터셋 검색 모드 (DocumentSearchService.SearchMode 복제). */
public enum DatasetSearchMode {
  SEMANTIC, // 벡터 코사인만
  KEYWORD, // pg_trgm 트라이그램만
  HYBRID // RRF 융합 (기본값)
}

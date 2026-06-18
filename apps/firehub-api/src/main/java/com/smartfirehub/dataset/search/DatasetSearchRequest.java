package com.smartfirehub.dataset.search;

/** POST /datasets/search 요청 body. */
public record DatasetSearchRequest(
    String query, // 필수
    Integer topK, // null → 기본 10, 최대 20
    DatasetSearchMode mode, // null → HYBRID
    String storageType) {} // null → 전체(TABLE+DOCUMENT)

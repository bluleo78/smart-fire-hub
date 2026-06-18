package com.smartfirehub.dataset.search;

/** 검색 결과 후보 1건. score 내림차순으로 반환. */
public record DatasetSearchHit(
    Long datasetId,
    String name,
    String description,
    String storageType, // "TABLE" | "DOCUMENT"
    String originType, // "SOURCE" | "DERIVED" | "TEMP"
    String tableName, // TABLE만; DOCUMENT은 null
    String category, // 카테고리명, 없으면 null
    double score) {} // RRF 점수(HYBRID) / 코사인 유사도 / word_similarity

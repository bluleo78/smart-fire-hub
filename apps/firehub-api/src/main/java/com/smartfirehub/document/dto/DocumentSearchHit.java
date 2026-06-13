package com.smartfirehub.document.dto;

/**
 * 검색 결과 청크 1건 (인용용). score 의미는 검색 모드에 따라 다르다 —
 * SEMANTIC=코사인 유사도, KEYWORD=word_similarity, HYBRID=RRF 점수(공통적으로 값이 클수록 관련성 높음).
 */
public record DocumentSearchHit(
    Long chunkId,
    Long documentFileId,
    Long datasetId,
    String fileName,
    int chunkIndex,
    String content,
    double score) {}

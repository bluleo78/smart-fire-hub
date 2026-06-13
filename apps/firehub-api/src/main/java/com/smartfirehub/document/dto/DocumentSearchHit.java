package com.smartfirehub.document.dto;

/** 검색 결과 청크 1건 (인용용). score는 코사인 유사도(1 - 거리, 1에 가까울수록 유사). */
public record DocumentSearchHit(
    Long chunkId,
    Long documentFileId,
    Long datasetId,
    String fileName,
    int chunkIndex,
    String content,
    double score) {}

package com.smartfirehub.document.dto;

/** 데이터셋 청크 bulk-read 응답 — 내부 GraphRAG 추출용. */
public record ChunkContentResponse(Long chunkId, String content) {}

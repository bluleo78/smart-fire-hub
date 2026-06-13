package com.smartfirehub.document.dto;

/** 청크 1개: 순번, 내용, 추정 토큰 수. */
public record Chunk(int index, String content, int tokenCount) {}

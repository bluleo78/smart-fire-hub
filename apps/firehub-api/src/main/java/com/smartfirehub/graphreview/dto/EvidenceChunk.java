package com.smartfirehub.graphreview.dto;

/** 판단 근거 — 검수 항목이 유래한 원문 청크 스니펫. */
public record EvidenceChunk(Long chunkId, String content) {}

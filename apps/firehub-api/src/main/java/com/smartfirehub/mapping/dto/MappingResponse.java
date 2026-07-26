package com.smartfirehub.mapping.dto;

// GET/PUT/activate 응답 — 매핑 문서 + 바인딩 온톨로지 id + 상태.
public record MappingResponse(long datasetId, long ontologyId, MappingSpec spec, String status) {}

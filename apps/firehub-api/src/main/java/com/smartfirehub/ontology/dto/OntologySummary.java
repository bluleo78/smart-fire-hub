package com.smartfirehub.ontology.dto;

// GET /api/v1/ontologies 목록 응답 — 본문(타입/관계) 없이 온톨로지 요약(id·도메인·스키마버전)만 담는다.
public record OntologySummary(Long id, String domain, int schemaVersion) {}

package com.smartfirehub.ontology.dto;

/**
 * GET /api/v1/ontology/{id}/graph-access 응답 — 현재 사용자가 이 온톨로지의 그래프를 읽을 수 있는가(WD-28). ai-agent 가
 * GraphReadableOntologyId 를 발급할지 정하는 데 쓴다. 스키마 응답(OntologyResponse)과 분리한 이유: ai-agent 가 스키마를 30초
 * 캐시하므로, 판정이 거기 실리면 등급 변경이 늦게 반영된다.
 */
public record GraphAccessResponse(boolean graphReadable) {}

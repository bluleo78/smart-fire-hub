package com.smartfirehub.ontology.dto;

// GET /api/v1/datasets/{id}/ontology 응답 — 바인딩된 온톨로지 id(미바인딩이면 ontologyId=null).
public record DatasetOntologyResponse(Long datasetId, Long ontologyId) {}

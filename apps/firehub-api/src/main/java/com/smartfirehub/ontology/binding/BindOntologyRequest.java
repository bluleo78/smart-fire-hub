package com.smartfirehub.ontology.binding;

// PUT /api/v1/datasets/{id}/ontology 요청 body — 데이터셋을 바인딩할 온톨로지 id.
public record BindOntologyRequest(Long ontologyId) {}

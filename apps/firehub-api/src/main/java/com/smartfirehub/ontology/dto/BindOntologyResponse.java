package com.smartfirehub.ontology.dto;

// PUT /api/v1/datasets/{id}/ontology 응답 — GET 응답(DatasetOntologyResponse)의 상위 집합.
// WD-184: 다른 지식 모델로 다시 연결하면 옛 모델 기준 매핑(dataset_mapping)을 같은 트랜잭션에서 지운다. 호출자(웹·AI 도구)가
// "이전 모델(○○) 기준 매핑을 삭제했습니다"를 알릴 수 있게 삭제 여부와 이전 모델을 함께 싣는다.
// previousOntologyId/previousOntologyName 은 이번 연결 직전의 연결 모델(첫 연결이면 null)이고, 이름은 모델의 도메인이다.
public record BindOntologyResponse(
    Long datasetId,
    Long ontologyId,
    boolean mappingRemoved,
    Long previousOntologyId,
    String previousOntologyName) {}

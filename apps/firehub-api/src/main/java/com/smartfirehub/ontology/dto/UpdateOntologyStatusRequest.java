package com.smartfirehub.ontology.dto;

// PATCH /api/v1/ontology/{id}/status 요청 DTO — 상태만 바꾸는 전용 계약.
// 전이를 full-document PUT에 얹지 않고 분리한 이유:
//  ① PUT은 schema_version을 무조건 +1 한다. 스키마가 그대로인 전이까지 버전을 올리면 이미 적재된
//     노드의 schemaVersion 스탬프가 전부 "구버전"으로 뒤집힌다.
//  ② 스키마 UPDATE와 상태 UPDATE가 분리된 두 문장이라 원자적이지 않았다(앞이 커밋되고 뒤가 실패 가능).
//  ③ 감사 로그가 전이를 ONTOLOGY_UPDATE(스키마 편집)로 오분류했다.
//  ④ 프론트가 전이를 위해 대상 온톨로지 본문을 먼저 조회해야 했다(관리 목록에서 행마다 N회 조회).
public record UpdateOntologyStatusRequest(String status) {}

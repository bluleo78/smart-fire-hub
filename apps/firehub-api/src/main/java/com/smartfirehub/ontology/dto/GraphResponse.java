package com.smartfirehub.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

// GET /api/v1/ontology/graph 응답 DTO — ai-agent GET /agent/graph 와 1:1 매핑(순수 프록시 계약).
public record GraphResponse(List<Node> nodes, List<Edge> edges) {
  // 지식그래프 노드: 키·타입·이름·해당 노드를 뒷받침하는 소스 청크 수·적재 당시 온톨로지 schema_version·온톨로지 id.
  // schemaVersion은 스탬프 도입(5-4) 이전 레거시 노드에는 없어 null일 수 있다(값 없음과 구버전을 구분).
  // ontologyId: 이 노드가 실제로 적재된 온톨로지 id. 스탬프 도입(#678) 이전 레거시 노드는 null.
  // sourceDatasetIds: 표(데이터셋) 투영 노드의 출처 데이터셋 id, properties: 노드에 적재된 사용자 속성(예: 피해액) — WD-382.
  // 문서 노드처럼 값이 없으면 응답에서 뺀다(노드 수만큼 null 을 실어 그래프 전체 응답을 키우지 않게).
  public record Node(
      String key,
      String type,
      String name,
      int sourceChunkCount,
      Integer schemaVersion,
      Long ontologyId,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<Long> sourceDatasetIds,
      @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> properties) {}

  // 지식그래프 엣지: 주어 노드 키-관계타입-목적어 노드 키.
  public record Edge(String subjectKey, String type, String objectKey) {}
}

package com.smartfirehub.ontology.dto;

import java.util.List;

// GET /api/v1/ontology/graph 응답 DTO — ai-agent GET /agent/graph 와 1:1 매핑(순수 프록시 계약).
public record GraphResponse(List<Node> nodes, List<Edge> edges) {
  // 지식그래프 노드: 키·타입·이름·해당 노드를 뒷받침하는 소스 청크 수.
  public record Node(String key, String type, String name, int sourceChunkCount) {}

  // 지식그래프 엣지: 주어 노드 키-관계타입-목적어 노드 키.
  public record Edge(String subjectKey, String type, String objectKey) {}
}

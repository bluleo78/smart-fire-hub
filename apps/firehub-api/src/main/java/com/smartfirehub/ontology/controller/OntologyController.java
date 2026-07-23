package com.smartfirehub.ontology.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import com.smartfirehub.ontology.service.OntologyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// 온톨로지 시각화(읽기 전용) — 스키마는 api DB 조회, 전체 그래프는 ai-agent로 프록시한다.
@RestController
@RequestMapping("/api/v1/ontology")
@RequiredArgsConstructor
public class OntologyController {
  private final OntologyService ontologyService;

  // 온톨로지 스키마(엔티티 타입/관계 트리플) 조회.
  @GetMapping
  @RequirePermission("dataset:read")
  public OntologyResponse getOntology() {
    return ontologyService.getOntology();
  }

  // 전체 적재 지식그래프(노드/엣지) 조회.
  @GetMapping("/graph")
  @RequirePermission("dataset:read")
  public GraphResponse getGraph() {
    return ontologyService.getGraph();
  }

  // 지식 모델 편집(B-2b) — full-document 교체 + schema_version 원자 증가. 편집은 특권(ontology:write, ADMIN).
  // 낙관적 동시성: 요청의 schemaVersion이 현재 DB 버전과 일치할 때만 적용(불일치 시 409).
  @PutMapping
  @RequirePermission("ontology:write")
  public OntologyResponse updateOntology(@RequestBody UpdateOntologyRequest request) {
    return ontologyService.updateOntology(request);
  }
}

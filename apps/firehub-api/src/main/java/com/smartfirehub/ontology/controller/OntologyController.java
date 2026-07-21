package com.smartfirehub.ontology.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.service.OntologyProxyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// 온톨로지 시각화(읽기 전용) — 스키마·전체 그래프 조회. ai-agent로 프록시한다.
@RestController
@RequestMapping("/api/v1/ontology")
@RequiredArgsConstructor
public class OntologyController {
  private final OntologyProxyService proxyService;

  // 온톨로지 스키마(엔티티 타입/관계 트리플) 조회.
  @GetMapping
  @RequirePermission("dataset:read")
  public OntologyResponse getOntology() {
    return proxyService.getOntology();
  }

  // 전체 적재 지식그래프(노드/엣지) 조회.
  @GetMapping("/graph")
  @RequirePermission("dataset:read")
  public GraphResponse getGraph() {
    return proxyService.getGraph();
  }
}

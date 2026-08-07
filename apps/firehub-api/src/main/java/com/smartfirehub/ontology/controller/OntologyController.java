package com.smartfirehub.ontology.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import com.smartfirehub.ontology.dto.UpdateOntologyStatusRequest;
import com.smartfirehub.ontology.service.OntologyService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 온톨로지 CRUD — 다중 온톨로지 지원(Task 3). 클래스 매핑을 /api/v1로 올려 복수형 /ontologies(목록·생성)와
// 단수형 /ontology/{id}(단건 조회·편집)를 함께 표현한다. 기존 GET/PUT /ontology(id=1 하위호환)와
// GET /ontology/graph(ai-agent 프록시)는 문서 파이프라인 등 기존 호출부 회귀 방지를 위해 그대로 유지한다.
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OntologyController {
  private final OntologyService ontologyService;

  // [하위호환] 기본 온톨로지(화재조사 id=1) 스키마 — 문서 파이프라인 프록시가 사용.
  @GetMapping("/ontology")
  @RequirePermission("dataset:read")
  public OntologyResponse getOntology() {
    return ontologyService.getOntology();
  }

  // 전체 적재 지식그래프(노드/엣지) — ai-agent 프록시.
  @GetMapping("/ontology/graph")
  @RequirePermission("dataset:read")
  public GraphResponse getGraph() {
    return ontologyService.getGraph();
  }

  // [하위호환] 기본 온톨로지(id=1) 편집.
  @PutMapping("/ontology")
  @RequirePermission("ontology:write")
  public OntologyResponse updateOntology(@RequestBody UpdateOntologyRequest request) {
    return ontologyService.updateOntology(request);
  }

  // 온톨로지 목록(요약). status 미지정 시 active만 — 바인딩 후보로 쓰이는 것이 이 목록의 주 용도다.
  // 관리 화면은 ?status=all 로 전체를 조회한다.
  @GetMapping("/ontologies")
  @RequirePermission("dataset:read")
  public List<OntologySummary> listOntologies(@RequestParam(required = false) String status) {
    return ontologyService.listOntologies(status);
  }

  // id 스코프 단건 조회.
  @GetMapping("/ontology/{id}")
  @RequirePermission("dataset:read")
  public OntologyResponse getById(@PathVariable Long id) {
    return ontologyService.getById(id);
  }

  // 신규 온톨로지 생성(ADMIN 특권). 생성된 id를 201로 반환.
  @PostMapping("/ontologies")
  @RequirePermission("ontology:write")
  public ResponseEntity<Long> create(@RequestBody CreateOntologyRequest request) {
    long id = ontologyService.createOntology(request);
    return ResponseEntity.status(201).body(id);
  }

  // id 스코프 편집(ADMIN 특권).
  @PutMapping("/ontology/{id}")
  @RequirePermission("ontology:write")
  public OntologyResponse updateById(
      @PathVariable Long id, @RequestBody UpdateOntologyRequest request) {
    return ontologyService.updateOntology(id, request);
  }

  // 상태 전이(활성화/은퇴/복귀, ADMIN 특권). 스키마 편집(PUT)과 분리된 전용 경로 —
  // 전이가 schema_version을 올리지 않고, 단일 UPDATE라 원자적이며, 호출부가 본문을 먼저 조회할 필요가 없다.
  // 허용되지 않는 전이는 409, 알 수 없는 상태·미완성 스키마 활성화는 400.
  @PatchMapping("/ontology/{id}/status")
  @RequirePermission("ontology:write")
  public ResponseEntity<Void> changeStatus(
      @PathVariable Long id, @RequestBody UpdateOntologyStatusRequest request) {
    ontologyService.changeStatus(id, request.status());
    return ResponseEntity.noContent().build();
  }

  // 온톨로지 삭제(ADMIN 특권). 참조 중이거나 기본 온톨로지면 409.
  @DeleteMapping("/ontology/{id}")
  @RequirePermission("ontology:write")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    ontologyService.deleteOntology(id);
    return ResponseEntity.noContent().build();
  }
}

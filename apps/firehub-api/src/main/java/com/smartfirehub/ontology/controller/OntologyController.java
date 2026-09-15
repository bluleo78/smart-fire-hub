package com.smartfirehub.ontology.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.GraphResponse;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.ontology.dto.UpdateOntologyStatusRequest;
import com.smartfirehub.ontology.service.OntologyService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 온톨로지 CRUD — 다중 온톨로지 지원(Task 3). 클래스 매핑을 /api/v1로 올려 복수형 /ontologies(목록·생성)와
// 단수형 /ontology/{id}(단건 조회·상태전이·삭제)를 함께 표현한다. GET /ontology/graph(ai-agent 프록시)는
// 기존 호출부 회귀 방지를 위해 그대로 유지한다.
// (S2 Task 7) 전체 스키마 교체 PUT /ontology, PUT /ontology/{id}는 요소 단위 편집 API
// (OntologyElementController)로 완전히 대체되어 삭제됐다 — 웹이 더 이상 호출하지 않는다.
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OntologyController {
  private final OntologyService ontologyService;

  // 전체 적재 지식그래프(노드/엣지) — ai-agent 프록시.
  @GetMapping("/ontology/graph")
  @RequirePermission("dataset:read")
  public GraphResponse getGraph() {
    return ontologyService.getGraph();
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

  // 온톨로지 삭제(ADMIN 특권). 참조 중(countReferences>0)이면 409 — id 무관하게 균일한 규칙이다(#678,
  // "기본 온톨로지" id=1 특별 취급 삭제 가드는 제거됨).
  @DeleteMapping("/ontology/{id}")
  @RequirePermission("ontology:write")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    ontologyService.deleteOntology(id);
    return ResponseEntity.noContent().build();
  }
}

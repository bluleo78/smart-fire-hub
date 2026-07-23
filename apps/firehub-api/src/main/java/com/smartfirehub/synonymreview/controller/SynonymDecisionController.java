package com.smartfirehub.synonymreview.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.synonymreview.dto.PendingSynonymRequest;
import com.smartfirehub.synonymreview.dto.SynonymDecisionResponse;
import com.smartfirehub.synonymreview.dto.SynonymLookupResponse;
import com.smartfirehub.synonymreview.service.SynonymDecisionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** HITL 근접쌍 검수 API — ai-agent(조회/등록) + firehub-web(목록/승인/거부) 공용. */
@RestController
@RequestMapping("/api/v1/graphrag/synonym-decisions")
@RequiredArgsConstructor
public class SynonymDecisionController {
  private final SynonymDecisionService service;

  // 근접쌍 기존 결정 조회 — ingest 중 ai-agent가 LLM 호출 전에 먼저 확인한다.
  @GetMapping("/lookup")
  @RequirePermission("dataset:read")
  public SynonymLookupResponse lookup(
      @RequestParam String entityType, @RequestParam String nameA, @RequestParam String nameB) {
    return new SynonymLookupResponse(service.lookupDecision(entityType, nameA, nameB));
  }

  // LLM "같다" 판정 근접쌍 등록 — ai-agent가 ingest 중 호출한다.
  @PostMapping("/pending")
  @RequirePermission("dataset:write")
  public void recordPending(@RequestBody PendingSynonymRequest req) {
    service.recordPending(req.entityType(), req.nameA(), req.nameB(), req.similarity(), req.rationale());
  }

  // 검수 대기 목록 — firehub-web 검수 페이지.
  @GetMapping
  @RequirePermission("dataset:read")
  public List<SynonymDecisionResponse> listPending(@RequestParam(required = false) String status) {
    return service.listPending();
  }

  // 승인 — Neo4j 동기 병합 후 status 갱신.
  @PostMapping("/{id}/approve")
  @RequirePermission("dataset:write")
  public SynonymDecisionResponse approve(@PathVariable long id, Authentication authentication) {
    return service.approve(id, (Long) authentication.getPrincipal());
  }

  // 거부 — DB status만 갱신.
  @PostMapping("/{id}/reject")
  @RequirePermission("dataset:write")
  public SynonymDecisionResponse reject(@PathVariable long id, Authentication authentication) {
    return service.reject(id, (Long) authentication.getPrincipal());
  }
}

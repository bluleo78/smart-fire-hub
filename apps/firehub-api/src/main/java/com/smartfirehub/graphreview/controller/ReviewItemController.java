package com.smartfirehub.graphreview.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.graphreview.dto.DecideRequest;
import com.smartfirehub.graphreview.dto.EvidenceChunk;
import com.smartfirehub.graphreview.dto.PendingPropertyRequest;
import com.smartfirehub.graphreview.dto.PendingSynonymRequest;
import com.smartfirehub.graphreview.dto.ReviewItemResponse;
import com.smartfirehub.graphreview.dto.SynonymLookupResponse;
import com.smartfirehub.graphreview.service.ReviewItemService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 범용 AI 검수 인박스 API — ai-agent(등록/조회) + firehub-web(목록/승인/거부/근거) 공용. */
@RestController
@RequestMapping("/api/v1/graphrag/review-items")
@RequiredArgsConstructor
public class ReviewItemController {
  private final ReviewItemService service;

  // 동의어 근접쌍 기존 결정 조회 — ingest 중 ai-agent가 LLM 호출 전에 확인.
  @GetMapping("/synonym/lookup")
  @RequirePermission("dataset:read")
  public SynonymLookupResponse lookupSynonym(
      @RequestParam String entityType, @RequestParam String nameA, @RequestParam String nameB) {
    return new SynonymLookupResponse(service.lookupSynonym(entityType, nameA, nameB));
  }

  // 동의어 근접쌍 등록 — ai-agent ingest 중 호출.
  @PostMapping("/synonym/pending")
  @RequirePermission("dataset:write")
  public void recordSynonym(@RequestBody PendingSynonymRequest req) {
    service.recordPendingSynonym(req.entityType(), req.nameA(), req.nameB(),
        req.similarity(), req.rationale(), req.datasetId(), req.sourceChunkIds());
  }

  // 속성 정규화 실패 등록 — ai-agent ingest 중 호출.
  @PostMapping("/property/pending")
  @RequirePermission("dataset:write")
  public void recordProperty(@RequestBody PendingPropertyRequest req) {
    service.recordPendingProperty(req.datasetId(), req.chunkId(), req.entityKey(), req.entityType(),
        req.propertyName(), req.dataType(), req.rawText());
  }

  // 검수 대기 목록 — status/itemType 필터(둘 다 선택).
  @GetMapping
  @RequirePermission("dataset:read")
  public List<ReviewItemResponse> listPending(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String itemType) {
    return service.listPending(itemType);
  }

  // 승인 — item_type별 그래프 변경 후 status 갱신. 속성은 correctedValue 필요.
  @PostMapping("/{id}/approve")
  @RequirePermission("dataset:write")
  public ReviewItemResponse approve(
      @PathVariable long id, @RequestBody(required = false) DecideRequest req, Authentication auth) {
    String corrected = req == null ? null : req.correctedValue();
    return service.approve(id, corrected, (Long) auth.getPrincipal());
  }

  // 거부 — DB status만 갱신.
  @PostMapping("/{id}/reject")
  @RequirePermission("dataset:write")
  public ReviewItemResponse reject(@PathVariable long id, Authentication auth) {
    return service.reject(id, (Long) auth.getPrincipal());
  }

  // 판단 근거 — 항목이 유래한 원문 청크 스니펫.
  @GetMapping("/{id}/evidence")
  @RequirePermission("dataset:read")
  public List<EvidenceChunk> evidence(@PathVariable long id) {
    return service.evidence(id);
  }
}

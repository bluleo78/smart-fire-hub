package com.smartfirehub.graphreview.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.graphreview.dto.DecideRequest;
import com.smartfirehub.graphreview.dto.EntityLookupResponse;
import com.smartfirehub.graphreview.dto.EvidenceChunk;
import com.smartfirehub.graphreview.dto.PendingEntityRequest;
import com.smartfirehub.graphreview.dto.PendingPropertyRequest;
import com.smartfirehub.graphreview.dto.PendingRelationRequest;
import com.smartfirehub.graphreview.dto.PendingSynonymRequest;
import com.smartfirehub.graphreview.dto.RelationLookupResponse;
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

  // 저신뢰 엔티티 등록 — ai-agent ingest 중 호출.
  @PostMapping("/entity/pending")
  @RequirePermission("dataset:write")
  public void recordEntity(@RequestBody PendingEntityRequest req) {
    service.recordPendingEntity(req.datasetId(), req.entityType(), req.name(), req.properties(),
        req.sourceChunkIds(), req.confidence(), req.reason(), req.relations());
  }

  // 저신뢰 엔티티 기존 결정 조회 — ingest 중 ai-agent가 보류 판정 시 확인.
  @GetMapping("/entity/lookup")
  @RequirePermission("dataset:read")
  public EntityLookupResponse lookupEntity(@RequestParam String entityType, @RequestParam String name) {
    return new EntityLookupResponse(service.lookupEntity(entityType, name));
  }

  // 저신뢰 관계 등록 — ai-agent ingest 중 호출.
  @PostMapping("/relation/pending")
  @RequirePermission("dataset:write")
  public void recordRelation(@RequestBody PendingRelationRequest req) {
    service.recordPendingRelation(req.datasetId(), req.subjectKey(), req.relType(), req.objectKey(),
        req.subjectName(), req.objectName(), req.sourceChunkIds(), req.confidence(), req.reason());
  }

  // 저신뢰 관계 기존 결정 조회 — ingest 중 ai-agent가 보류 판정 시 확인.
  @GetMapping("/relation/lookup")
  @RequirePermission("dataset:read")
  public RelationLookupResponse lookupRelation(
      @RequestParam String subjectKey, @RequestParam String relType, @RequestParam String objectKey) {
    return new RelationLookupResponse(service.lookupRelation(subjectKey, relType, objectKey));
  }

  // 검수 항목 목록 — status/itemType 필터(둘 다 선택). status 생략 시 pending(#318 이전과 동일한 기본값),
  // 허용되지 않은 status는 400. 예전에는 status를 받고도 버려 approved/rejected 요청에 pending을 돌려줬다.
  @GetMapping
  @RequirePermission("dataset:read")
  public List<ReviewItemResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String itemType) {
    return service.list(status, itemType);
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

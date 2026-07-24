package com.smartfirehub.graphreview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.graphreview.dto.EntityRelationRef;
import com.smartfirehub.graphreview.dto.EvidenceChunk;
import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.dto.ReviewItemResponse;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

/** 범용 검수 인박스 서비스 — 타입별 등록/조회, 승인 시 item_type별 액션 라우팅, 원문 근거 조회. */
@Service
@RequiredArgsConstructor
public class ReviewItemService {

  private final ReviewItemRepository repo;
  private final GraphMutationClient mutationClient;
  private final DocumentChunkRepository chunkRepository;
  private final ObjectMapper objectMapper;

  static final String SYNONYM = "synonym_merge";
  static final String PROPERTY = "property_normalization";
  static final String ENTITY = "entity_extraction";

  // resolver.ts normalizeName과 동일 규칙(trim + 연속공백 1칸 + 소문자) — 정렬 키로만 사용, 저장은 원본(trim).
  private static String normalize(String s) {
    return s.trim().replaceAll("\\s+", " ").toLowerCase();
  }

  /** 동의어 근접쌍 등록 — 이름쌍을 정규화 비교로 정렬(순서 무관 dedupe) 후 payload/dedupe_key 구성.
   *  datasetId/sourceChunkIds가 오면 원문 근거(evidence)용으로 함께 기록한다(신규-only, first-writer-wins). */
  @SneakyThrows
  public void recordPendingSynonym(String entityType, String rawA, String rawB, Double similarity, String rationale,
      Long datasetId, List<Long> sourceChunkIds) {
    String a = rawA.trim();
    String b = rawB.trim();
    if (normalize(a).compareTo(normalize(b)) > 0) { String t = a; a = b; b = t; }
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("entityType", entityType);
    payload.put("nameA", a);
    payload.put("nameB", b);
    // 속성 경로와 동일하게 정수 chunkId 배열로 기록(evidence()가 asLong으로 읽음).
    if (sourceChunkIds != null && !sourceChunkIds.isEmpty()) {
      var arr = payload.putArray("sourceChunkIds");
      for (Long c : sourceChunkIds) if (c != null) arr.add(c.longValue());
    }
    String dedupe = entityType + "|" + a + "|" + b;
    repo.upsertPending(SYNONYM, dedupe, datasetId, "similarity", similarity, rationale, objectMapper.writeValueAsString(payload));
  }

  /** 동의어 근접쌍 기존 결정 조회 — 없으면 "none". */
  public String lookupSynonym(String entityType, String rawA, String rawB) {
    String a = rawA.trim();
    String b = rawB.trim();
    if (normalize(a).compareTo(normalize(b)) > 0) { String t = a; a = b; b = t; }
    return repo.findDecisionStatus(SYNONYM, entityType + "|" + a + "|" + b).orElse("none");
  }

  /** 속성 정규화 실패 등록 — entityKey는 canonical 재매핑 후 최종 key(정정 write 대상). */
  @SneakyThrows
  public void recordPendingProperty(
      Long datasetId, Long chunkId, String entityKey, String entityType,
      String propertyName, String dataType, String rawText) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("entityKey", entityKey);
    payload.put("entityType", entityType);
    payload.put("propertyName", propertyName);
    payload.put("dataType", dataType);
    payload.put("rawText", rawText);
    if (chunkId != null) payload.putArray("sourceChunkIds").add(chunkId);
    String dedupe = entityKey + "|" + propertyName;
    String reason = "'" + rawText + "' 값을 " + dataType + " 타입으로 정규화하지 못했습니다.";
    repo.upsertPending(PROPERTY, dedupe, datasetId, "normalization_failure", null, reason,
        objectMapper.writeValueAsString(payload));
  }

  /** 저신뢰 엔티티 검수 등록 — dedupe_key는 as-extracted 정체성(entityType|정규화이름), signal_score=confidence. */
  @SneakyThrows
  public void recordPendingEntity(
      Long datasetId, String entityType, String name, JsonNode properties,
      List<Long> sourceChunkIds, Double confidence, String reason, List<EntityRelationRef> relations) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("entityType", entityType);
    payload.put("name", name);
    if (properties != null && !properties.isNull()) payload.set("properties", properties);
    if (sourceChunkIds != null && !sourceChunkIds.isEmpty()) {
      var arr = payload.putArray("sourceChunkIds");
      for (Long c : sourceChunkIds) if (c != null) arr.add(c.longValue());
    }
    var relArr = payload.putArray("relations");
    if (relations != null) {
      for (EntityRelationRef r : relations) {
        ObjectNode ro = objectMapper.createObjectNode();
        ro.put("relType", r.relType());
        ro.put("direction", r.direction());
        ro.put("otherKey", r.otherKey());
        relArr.add(ro);
      }
    }
    String dedupe = entityType + "|" + normalize(name);
    String reasonMsg = (reason != null && !reason.isBlank()) ? reason : "추출 신뢰도가 낮은 엔티티입니다.";
    repo.upsertPending(ENTITY, dedupe, datasetId, "low_confidence", confidence, reasonMsg,
        objectMapper.writeValueAsString(payload));
  }

  /** 저신뢰 엔티티 기존 결정 조회 — 없으면 "none". dedupe_key는 recordPendingEntity와 동일 규칙. */
  public String lookupEntity(String entityType, String name) {
    return repo.findDecisionStatus(ENTITY, entityType + "|" + normalize(name)).orElse("none");
  }

  public List<ReviewItemResponse> listPending(String itemType) {
    return repo.findPending(itemType).stream().map(this::toResponse).toList();
  }

  /** 승인 — item_type별 그래프 변경을 먼저 수행하고, 성공해야 status를 approved로 갱신한다(실패 시 pending 유지). */
  public ReviewItemResponse approve(long id, String correctedValue, long userId) {
    ReviewItemRecord row = getPendingOrThrow(id);
    JsonNode p = parse(row.payloadJson());
    switch (row.itemType()) {
      case SYNONYM -> mutationClient.mergeEntities(
          p.path("entityType").asText(), p.path("nameA").asText(), p.path("nameB").asText());
      case PROPERTY -> {
        if (correctedValue == null || correctedValue.isBlank()) {
          throw new IllegalArgumentException("속성 정규화 승인에는 정정값(correctedValue)이 필요합니다.");
        }
        mutationClient.setProperty(
            p.path("entityKey").asText(), p.path("propertyName").asText(),
            p.path("dataType").asText(), correctedValue);
      }
      case ENTITY -> {
        // as-extracted 타입/이름 그대로 적재(정정 없음). 보류 관계는 add-entity가 끝점 존재 시에만 MERGE.
        JsonNode props = p.path("properties");
        List<Long> chunkIds = new ArrayList<>();
        p.path("sourceChunkIds").forEach(n -> chunkIds.add(n.asLong()));
        List<GraphMutationClient.RelationRef> rels = new ArrayList<>();
        p.path("relations").forEach(r -> rels.add(new GraphMutationClient.RelationRef(
            r.path("relType").asText(), r.path("direction").asText(), r.path("otherKey").asText())));
        mutationClient.addEntity(p.path("entityType").asText(), p.path("name").asText(),
            props.isMissingNode() ? null : props, chunkIds, rels);
      }
      default -> throw new IllegalStateException("알 수 없는 item_type: " + row.itemType());
    }
    repo.updateStatus(id, "approved", userId);
    return toResponse(repo.findById(id).orElseThrow());
  }

  /** 거부 — 그래프 변경 없이 status만 rejected로 갱신(속성은 값 없음 유지, 동의어는 별개 유지). */
  public ReviewItemResponse reject(long id, long userId) {
    getPendingOrThrow(id);
    repo.updateStatus(id, "rejected", userId);
    return toResponse(repo.findById(id).orElseThrow());
  }

  /** 판단 근거 — dataset_id로 청크 전체를 읽어 payload.sourceChunkIds에 해당하는 원문만 반환. */
  public List<EvidenceChunk> evidence(long id) {
    ReviewItemRecord row = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("검수 항목을 찾을 수 없습니다: " + id));
    if (row.datasetId() == null) return List.of();
    JsonNode ids = parse(row.payloadJson()).path("sourceChunkIds");
    if (!ids.isArray() || ids.isEmpty()) return List.of();
    Set<Long> want = new java.util.HashSet<>();
    ids.forEach(n -> want.add(n.asLong()));
    List<EvidenceChunk> out = new ArrayList<>();
    for (var c : chunkRepository.findChunkContentsByDataset(row.datasetId())) {
      if (want.contains(c.chunkId())) out.add(new EvidenceChunk(c.chunkId(), c.content()));
    }
    return out;
  }

  private ReviewItemRecord getPendingOrThrow(long id) {
    ReviewItemRecord row = repo.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("검수 항목을 찾을 수 없습니다: " + id));
    if (!"pending".equals(row.status())) {
      throw new IllegalStateException("이미 처리된 항목입니다(status=" + row.status() + "): " + id);
    }
    return row;
  }

  @SneakyThrows
  private JsonNode parse(String json) {
    return objectMapper.readTree(json == null ? "{}" : json);
  }

  @SneakyThrows
  private ReviewItemResponse toResponse(ReviewItemRecord r) {
    return new ReviewItemResponse(
        r.id(), r.itemType(), r.status(), r.datasetId(), r.signalType(), r.signalScore(), r.reason(),
        parse(r.payloadJson()), r.decidedBy(),
        r.decidedAt() == null ? null : r.decidedAt().toString(),
        r.createdAt() == null ? null : r.createdAt().toString());
  }
}

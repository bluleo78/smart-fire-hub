package com.smartfirehub.graphreview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.graphreview.dto.EntityRelationRef;
import com.smartfirehub.graphreview.dto.EvidenceChunk;
import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.dto.ReviewItemResponse;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 범용 검수 인박스 서비스 — 타입별 등록/조회, 승인 시 item_type별 액션 라우팅, 원문 근거 조회.
 *
 * <p>보안 등급(최종 리뷰 C2): 검수 항목은 출처 데이터셋(dataset_id)의 청크 원문·엔티티 이름을 싣는다. 경로가 {@code
 * /api/v1/datasets/**} 밖이라 데이터셋 인터셉터가 닿지 않으므로, 조회자가 출처 데이터셋을 볼 수 없는 항목은 목록에서 빼고 근거·승인·거부는 <b>없는 항목과
 * 같은</b> 404 로 거부한다(존재 은닉). dataset_id 가 없는 레거시 항목은 판정할 데이터셋이 없어 기존대로 둔다.
 */
@Service
@RequiredArgsConstructor
public class ReviewItemService {

  private final ReviewItemRepository repo;
  private final GraphMutationClient mutationClient;
  private final DocumentChunkRepository chunkRepository;
  private final ObjectMapper objectMapper;
  private final DatasetAccessGuard datasetAccessGuard;
  private final ClearanceResolver clearanceResolver;

  /** 없는 항목·볼 수 없는 출처 데이터셋의 항목 — 둘을 구분하지 않는 단일 404 코드. */
  public static final String NOT_FOUND_CODE = "REVIEW_ITEM_NOT_FOUND";

  static final String SYNONYM = "synonym_merge";
  static final String PROPERTY = "property_normalization";
  static final String ENTITY = "entity_extraction";
  static final String RELATION = "relation_extraction";

  // resolver.ts normalizeName과 동일 규칙(trim + 연속공백 1칸 + 소문자) — 정렬 키로만 사용, 저장은 원본(trim).
  private static String normalize(String s) {
    return s.trim().replaceAll("\\s+", " ").toLowerCase();
  }

  /**
   * 동의어 근접쌍 등록 — 이름쌍을 정규화 비교로 정렬(순서 무관 dedupe) 후 payload/dedupe_key 구성. datasetId/sourceChunkIds가
   * 오면 원문 근거(evidence)용으로 함께 기록한다(신규-only, first-writer-wins).
   */
  @SneakyThrows
  public void recordPendingSynonym(
      String entityType,
      String rawA,
      String rawB,
      Double similarity,
      String rationale,
      Long datasetId,
      List<Long> sourceChunkIds) {
    requireSource(datasetId, sourceChunkIds);
    String a = rawA.trim();
    String b = rawB.trim();
    if (normalize(a).compareTo(normalize(b)) > 0) {
      String t = a;
      a = b;
      b = t;
    }
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
    repo.upsertPending(
        SYNONYM,
        dedupe,
        datasetId,
        "similarity",
        similarity,
        rationale,
        objectMapper.writeValueAsString(payload));
  }

  /** 동의어 근접쌍 기존 결정 조회 — 없으면 "none". */
  public String lookupSynonym(String entityType, String rawA, String rawB) {
    String a = rawA.trim();
    String b = rawB.trim();
    if (normalize(a).compareTo(normalize(b)) > 0) {
      String t = a;
      a = b;
      b = t;
    }
    return repo.findDecisionStatus(
            SYNONYM, entityType + "|" + a + "|" + b, datasetAccessGuard.visibleCondition())
        .orElse("none");
  }

  /** 속성 정규화 실패 등록 — entityKey는 canonical 재매핑 후 최종 key(정정 write 대상). */
  @SneakyThrows
  public void recordPendingProperty(
      Long datasetId,
      Long chunkId,
      String entityKey,
      String entityType,
      String propertyName,
      String dataType,
      String rawText) {
    requireSource(datasetId, chunkId == null ? List.of() : List.of(chunkId));
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("entityKey", entityKey);
    payload.put("entityType", entityType);
    payload.put("propertyName", propertyName);
    payload.put("dataType", dataType);
    payload.put("rawText", rawText);
    if (chunkId != null) payload.putArray("sourceChunkIds").add(chunkId);
    String dedupe = entityKey + "|" + propertyName;
    String reason = "'" + rawText + "' 값을 " + dataType + " 타입으로 정규화하지 못했습니다.";
    repo.upsertPending(
        PROPERTY,
        dedupe,
        datasetId,
        "normalization_failure",
        null,
        reason,
        objectMapper.writeValueAsString(payload));
  }

  /** 저신뢰 엔티티 검수 등록 — dedupe_key는 as-extracted 정체성(entityType|정규화이름), signal_score=confidence. */
  @SneakyThrows
  public void recordPendingEntity(
      Long datasetId,
      String entityType,
      String name,
      JsonNode properties,
      List<Long> sourceChunkIds,
      Double confidence,
      String reason,
      List<EntityRelationRef> relations) {
    requireSource(datasetId, sourceChunkIds);
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
    repo.upsertPending(
        ENTITY,
        dedupe,
        datasetId,
        "low_confidence",
        confidence,
        reasonMsg,
        objectMapper.writeValueAsString(payload));
  }

  /** 저신뢰 엔티티 기존 결정 조회 — 없으면 "none". dedupe_key는 recordPendingEntity와 동일 규칙. */
  public String lookupEntity(String entityType, String name) {
    return repo.findDecisionStatus(
            ENTITY, entityType + "|" + normalize(name), datasetAccessGuard.visibleCondition())
        .orElse("none");
  }

  /** 저신뢰 관계 검수 등록 — dedupe_key는 ai-agent가 계산한 canonical subjectKey|relType|objectKey(opaque). */
  @SneakyThrows
  public void recordPendingRelation(
      Long datasetId,
      String subjectKey,
      String relType,
      String objectKey,
      String subjectName,
      String objectName,
      List<Long> sourceChunkIds,
      Double confidence,
      String reason) {
    requireSource(datasetId, sourceChunkIds);
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("subjectKey", subjectKey);
    payload.put("relType", relType);
    payload.put("objectKey", objectKey);
    payload.put("subjectName", subjectName);
    payload.put("objectName", objectName);
    if (sourceChunkIds != null && !sourceChunkIds.isEmpty()) {
      var arr = payload.putArray("sourceChunkIds");
      for (Long c : sourceChunkIds) if (c != null) arr.add(c.longValue());
    }
    String dedupe = subjectKey + "|" + relType + "|" + objectKey;
    String reasonMsg = (reason != null && !reason.isBlank()) ? reason : "추출 신뢰도가 낮은 관계입니다.";
    repo.upsertPending(
        RELATION,
        dedupe,
        datasetId,
        "low_confidence",
        confidence,
        reasonMsg,
        objectMapper.writeValueAsString(payload));
  }

  /** 저신뢰 관계 기존 결정 조회 — 없으면 "none". dedupe_key는 ai-agent 계산 opaque 값 그대로. */
  public String lookupRelation(String subjectKey, String relType, String objectKey) {
    return repo.findDecisionStatus(
            RELATION,
            subjectKey + "|" + relType + "|" + objectKey,
            datasetAccessGuard.visibleCondition())
        .orElse("none");
  }

  /** 조회 가능한 status 값 — 이 테이블에 실제로 쓰이는 값의 전부다(upsertPending/approve/reject). */
  static final Set<String> LIST_STATUSES = Set.of("pending", "approved", "rejected");

  /** size 파라미터의 상한 — pending 큐가 수천 건으로 불어나도 한 응답이 무제한으로 커지지 않게 막는다(#422). */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 검수 항목 목록 — status/itemType 필터(둘 다 선택) + page/size(둘 다 선택, opt-in).
   *
   * <p>status를 생략하면 pending이다(#318 이전의 유일한 동작이자 웹 인박스의 기본값 — 생략을 "전체"로 해석하면 파라미터 없이 호출하던 기존 호출자의
   * 결과가 조용히 달라진다). 허용되지 않은 값은 IllegalArgumentException(400)으로 거부한다 — 받아놓고 무시하면 조용한 오답이 된다.
   *
   * <p>size를 생략하면(page도 함께 무시) 기존과 동일하게 전체를 반환한다 — ai-agent(MCP)의 listReviewItems 호출자는 페이지 파라미터를
   * 보내지 않으므로 응답 스키마·개수 모두 그대로다(#422). size를 주면 1..{@link #MAX_PAGE_SIZE} 범위여야 하고, page는 0 이상이어야 한다.
   */
  public List<ReviewItemResponse> list(String status, String itemType, Integer page, Integer size) {
    String effective = (status == null || status.isBlank()) ? "pending" : status;
    if (!LIST_STATUSES.contains(effective)) {
      throw new IllegalArgumentException(
          "지원하지 않는 status 값입니다: " + status + " (허용: pending, approved, rejected)");
    }
    Integer offset = null;
    if (size != null) {
      if (size < 1 || size > MAX_PAGE_SIZE) {
        throw new IllegalArgumentException("size는 1.." + MAX_PAGE_SIZE + " 범위여야 합니다: " + size);
      }
      int effectivePage = (page == null) ? 0 : page;
      if (effectivePage < 0) {
        throw new IllegalArgumentException("page는 0 이상이어야 합니다: " + page);
      }
      offset = effectivePage * size;
    }
    // 출처 데이터셋을 볼 수 없는 항목은 SQL 에서 거른다 — 자바에서 거르면 limit/offset 페이지가 어긋난다.
    return repo
        .findByStatus(effective, itemType, offset, size, datasetAccessGuard.visibleCondition())
        .stream()
        .map(this::toResponse)
        .toList();
  }

  /** 승인 — item_type별 그래프 변경을 먼저 수행하고, 성공해야 status를 approved로 갱신한다(실패 시 pending 유지). */
  public ReviewItemResponse approve(long id, String correctedValue, long userId) {
    ReviewItemRecord row = getPendingOrThrow(id);
    JsonNode p = parse(row.payloadJson());
    // 네 타입 모두 datasetId가 필수다(사유는 requireDatasetId 문서 참고).
    requireDatasetId(row);
    switch (row.itemType()) {
      case SYNONYM ->
          mutationClient.mergeEntities(
              p.path("entityType").asText(),
              p.path("nameA").asText(),
              p.path("nameB").asText(),
              row.datasetId());
      case PROPERTY -> {
        if (correctedValue == null || correctedValue.isBlank()) {
          throw new IllegalArgumentException("속성 정규화 승인에는 정정값(correctedValue)이 필요합니다.");
        }
        mutationClient.setProperty(
            p.path("entityKey").asText(),
            p.path("propertyName").asText(),
            p.path("dataType").asText(),
            correctedValue,
            row.datasetId());
      }
      case ENTITY -> {
        // as-extracted 타입/이름 그대로 적재(정정 없음). 보류 관계는 add-entity가 끝점 존재 시에만 MERGE.
        JsonNode props = p.path("properties");
        List<Long> chunkIds = new ArrayList<>();
        p.path("sourceChunkIds").forEach(n -> chunkIds.add(n.asLong()));
        List<GraphMutationClient.RelationRef> rels = new ArrayList<>();
        p.path("relations")
            .forEach(
                r ->
                    rels.add(
                        new GraphMutationClient.RelationRef(
                            r.path("relType").asText(),
                            r.path("direction").asText(),
                            r.path("otherKey").asText())));
        mutationClient.addEntity(
            p.path("entityType").asText(),
            p.path("name").asText(),
            props.isMissingNode() ? null : props,
            chunkIds,
            rels,
            row.datasetId());
      }
      case RELATION -> {
        // as-extracted 관계 그대로 적재. add-relation이 양 끝점 존재 시에만 MERGE.
        List<Long> chunkIds = new ArrayList<>();
        p.path("sourceChunkIds").forEach(n -> chunkIds.add(n.asLong()));
        mutationClient.addRelation(
            p.path("subjectKey").asText(),
            p.path("relType").asText(),
            p.path("objectKey").asText(),
            chunkIds,
            row.datasetId());
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
  // RLS 가 걸린 document_chunk 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public List<EvidenceChunk> evidence(long id) {
    ReviewItemRecord row = findVisibleOrThrow(id);
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

  /** 근거 청크가 출처 데이터셋에 속하지 않을 때의 단일 400 메시지 — 없는 청크와 남의 청크를 구분하지 않는다(청크 존재 오라클 방지). */
  static final String SOURCE_CHUNK_MISMATCH = "출처 청크가 지정한 데이터셋에 속하지 않습니다.";

  /**
   * 검수 대기 등록 요청의 출처를 서버가 검증한다(후속 F3) — 예전에는 클라이언트가 보낸 datasetId 를 그대로 믿어, 볼 수 없는(또는 없는) 데이터셋에 항목을
   * 꽂아 그 데이터셋의 그래프 승인 대기열을 오염시킬 수 있었다.
   *
   * <ol>
   *   <li>datasetId 가 있으면 요청자가 그 데이터셋을 볼 수 있어야 한다. 못 보면 <b>없는 데이터셋과 같은</b> 404(requireView 의
   *       DatasetNotFoundException) — 숨김과 부재를 구분하지 않는다. dataset_id 에는 FK 가 없어 예전에는 없는 id 도 그대로
   *       저장됐다.
   *   <li>근거 청크 id 가 오면 전부 그 데이터셋의 청크여야 한다(없거나 다른 데이터셋이면 같은 400). 실제 호출자(ai-agent ingest)는 그 데이터셋의
   *       청크 목록에서 id 를 얻으므로 항상 통과한다 — 페이로드 계약은 바뀌지 않는다.
   *   <li>datasetId 없이 청크만 오면 근거를 판정할 데이터셋이 없어 400. 둘 다 없는 등록(레거시 호환)은 그대로 둔다.
   * </ol>
   */
  private void requireSource(Long datasetId, List<Long> chunkIds) {
    Set<Long> ids = new java.util.HashSet<>();
    if (chunkIds != null) for (Long c : chunkIds) if (c != null) ids.add(c);
    if (datasetId == null) {
      if (!ids.isEmpty()) throw new IllegalArgumentException(SOURCE_CHUNK_MISMATCH);
      return;
    }
    // 가시성을 청크 검사보다 먼저 본다 — 순서가 바뀌면 숨김 데이터셋에 대해 "청크 불일치(400)"와 "404"가 갈려 존재가 드러난다.
    datasetAccessGuard.requireView(datasetId);
    if (!ids.isEmpty() && chunkRepository.countChunksInDataset(datasetId, ids) != ids.size()) {
      throw new IllegalArgumentException(SOURCE_CHUNK_MISMATCH);
    }
  }

  /**
   * 네 항목 타입 모두 ai-agent 호출에 datasetId가 필수다(#678 — "기본 온톨로지" 폴백 제거로 ai-agent가 온톨로지를 고를 다른 방법이 없어졌다).
   * datasetId가 없는(레거시) 항목을 그대로 호출하면 ai-agent가 400을 반환하는데, 그건 승인 자체가 pending으로 남아 원인 파악이 어려운 실패다 —
   * 대신 여기서 미리 막아 "왜 승인이 안 되는지" 명확한 사유를 준다.
   *
   * <p>PROPERTY도 예외가 아니게 됐다: set-property가 datasetId로 해소한 온톨로지로 write를 스코프하게 바뀌었기 때문이다(그 전에는
   * entityKey만으로 남의 그래프 노드를 덮어쓸 수 있었다). 실적재 경로 (recordPendingProperty)는 항상 datasetId를 채우므로, 막히는 것은
   * 그 이전의 레거시 행뿐이다.
   */
  private void requireDatasetId(ReviewItemRecord row) {
    if (row.datasetId() == null) {
      throw new IllegalArgumentException("이 검수 항목에는 데이터셋 정보가 없어 승인할 수 없습니다.");
    }
  }

  /**
   * pending 항목을 가져온다. 볼 수 있는지(findVisibleOrThrow)를 상태 검사보다 <b>먼저</b> 본다 — 순서가 바뀌면 "이미 처리된
   * 항목입니다(status=…)" 응답이 숨김 항목의 존재와 상태를 드러낸다.
   */
  private ReviewItemRecord getPendingOrThrow(long id) {
    ReviewItemRecord row = findVisibleOrThrow(id);
    if (!"pending".equals(row.status())) {
      throw new IllegalStateException("이미 처리된 항목입니다(status=" + row.status() + "): " + id);
    }
    return row;
  }

  /**
   * 항목을 가져오되, 현재 사용자가 출처 데이터셋을 볼 수 없으면 없는 항목과 같은 404 를 던진다(존재 은닉 — 데이터셋 id 도 싣지 않는다). 그래서
   * DatasetAccessGuard#requireView(데이터셋 id 를 담은 다른 404)가 아니라 판정 값만 쓴다.
   */
  private ReviewItemRecord findVisibleOrThrow(long id) {
    ReviewItemRecord row = repo.findById(id).orElseThrow(() -> notFound(id));
    if (row.datasetId() != null
        && !datasetAccessGuard
            .check(clearanceResolver.current(), row.datasetId(), DatasetAction.VIEW, null)
            .allowed()) {
      throw notFound(id);
    }
    return row;
  }

  /** 없는 항목·숨김 항목 공통 404 — 바이트 단위로 같은 응답이어야 존재가 드러나지 않는다. */
  private static CodedApiException notFound(long id) {
    return new CodedApiException(HttpStatus.NOT_FOUND, NOT_FOUND_CODE, "검수 항목을 찾을 수 없습니다: " + id);
  }

  @SneakyThrows
  private JsonNode parse(String json) {
    return objectMapper.readTree(json == null ? "{}" : json);
  }

  @SneakyThrows
  private ReviewItemResponse toResponse(ReviewItemRecord r) {
    return new ReviewItemResponse(
        r.id(),
        r.itemType(),
        r.status(),
        r.datasetId(),
        r.signalType(),
        r.signalScore(),
        r.reason(),
        parse(r.payloadJson()),
        r.decidedBy(),
        r.decidedAt() == null ? null : r.decidedAt().toString(),
        r.createdAt() == null ? null : r.createdAt().toString());
  }
}

package com.smartfirehub.graphreview.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * graph_review_item 읽기/쓰기 — 범용 AI 검수 인박스.
 *
 * <p>jOOQ 코드젠에 의존하지 않는 plain-SQL {@code name()} 스타일(GraphIngestRepository 선례).
 * payload는 {@link JSONB}로 저장하고, Record에는 JSON 문자열로 실어 서비스가 Jackson으로 파싱한다.
 */
@Repository
@RequiredArgsConstructor
public class ReviewItemRepository {

  private final DSLContext dsl;

  private static final Table<?> T = table(name("graph_review_item"));
  private static final Field<Long> ID = field(name("graph_review_item", "id"), Long.class);
  private static final Field<String> ITEM_TYPE = field(name("graph_review_item", "item_type"), String.class);
  private static final Field<String> STATUS = field(name("graph_review_item", "status"), String.class);
  private static final Field<Long> DATASET_ID = field(name("graph_review_item", "dataset_id"), Long.class);
  private static final Field<String> SIGNAL_TYPE = field(name("graph_review_item", "signal_type"), String.class);
  private static final Field<Double> SIGNAL_SCORE = field(name("graph_review_item", "signal_score"), Double.class);
  private static final Field<String> REASON = field(name("graph_review_item", "reason"), String.class);
  private static final Field<JSONB> PAYLOAD = field(name("graph_review_item", "payload"), JSONB.class);
  private static final Field<String> DEDUPE_KEY = field(name("graph_review_item", "dedupe_key"), String.class);
  private static final Field<Long> DECIDED_BY = field(name("graph_review_item", "decided_by"), Long.class);
  private static final Field<LocalDateTime> DECIDED_AT = field(name("graph_review_item", "decided_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> CREATED_AT = field(name("graph_review_item", "created_at"), LocalDateTime.class);

  /** pending 항목을 upsert 등록한다. (item_type, dedupe_key)가 이미 있으면 무시한다(재적재 중복 방지). */
  public void upsertPending(
      String itemType, String dedupeKey, Long datasetId, String signalType,
      Double signalScore, String reason, String payloadJson) {
    dsl.insertInto(T)
        .set(ITEM_TYPE, itemType)
        .set(STATUS, "pending")
        .set(DATASET_ID, datasetId)
        .set(SIGNAL_TYPE, signalType)
        .set(SIGNAL_SCORE, signalScore)
        .set(REASON, reason)
        .set(PAYLOAD, JSONB.valueOf(payloadJson))
        .set(DEDUPE_KEY, dedupeKey)
        .onConflict(ITEM_TYPE, DEDUPE_KEY)
        .doNothing()
        .execute();
  }

  /** (item_type, dedupe_key)의 기존 결정 상태. 없으면 empty(ingest는 이를 LLM 재호출 신호로 해석). */
  public Optional<String> findDecisionStatus(String itemType, String dedupeKey) {
    return dsl.select(STATUS)
        .from(T)
        .where(ITEM_TYPE.eq(itemType).and(DEDUPE_KEY.eq(dedupeKey)))
        .fetchOptional(r -> r.get(STATUS));
  }

  /** 검수 대기(pending) 목록 — itemType이 주어지면 해당 타입만, null이면 전체. 등록순. */
  public List<ReviewItemRecord> findPending(String itemType) {
    Condition where = STATUS.eq("pending");
    if (itemType != null) where = where.and(ITEM_TYPE.eq(itemType));
    return dsl.select(ID, ITEM_TYPE, STATUS, DATASET_ID, SIGNAL_TYPE, SIGNAL_SCORE, REASON, PAYLOAD, DECIDED_BY, DECIDED_AT, CREATED_AT)
        .from(T)
        .where(where)
        .orderBy(CREATED_AT)
        .fetch(this::toRecord);
  }

  public Optional<ReviewItemRecord> findById(long id) {
    return dsl.select(ID, ITEM_TYPE, STATUS, DATASET_ID, SIGNAL_TYPE, SIGNAL_SCORE, REASON, PAYLOAD, DECIDED_BY, DECIDED_AT, CREATED_AT)
        .from(T)
        .where(ID.eq(id))
        .fetchOptional(this::toRecord);
  }

  /** 승인/거부 처리 — status·decided_by·decided_at 갱신. */
  public void updateStatus(long id, String status, long decidedBy) {
    dsl.update(T)
        .set(STATUS, status)
        .set(DECIDED_BY, decidedBy)
        .set(DECIDED_AT, LocalDateTime.now())
        .where(ID.eq(id))
        .execute();
  }

  private ReviewItemRecord toRecord(org.jooq.Record r) {
    JSONB payload = r.get(PAYLOAD);
    return new ReviewItemRecord(
        r.get(ID), r.get(ITEM_TYPE), r.get(STATUS), r.get(DATASET_ID), r.get(SIGNAL_TYPE),
        r.get(SIGNAL_SCORE), r.get(REASON), payload == null ? null : payload.data(),
        r.get(DECIDED_BY), r.get(DECIDED_AT), r.get(CREATED_AT));
  }
}

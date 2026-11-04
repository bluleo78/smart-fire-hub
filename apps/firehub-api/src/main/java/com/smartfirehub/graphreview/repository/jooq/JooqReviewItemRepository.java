package com.smartfirehub.graphreview.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReviewItemRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>graph_review_item 읽기/쓰기 — 범용 AI 검수 인박스.
 *
 * <p>jOOQ 코드젠에 의존하지 않는 plain-SQL {@code name()} 스타일(GraphIngestRepository 선례). payload는 {@link
 * JSONB}로 저장하고, Record에는 JSON 문자열로 실어 서비스가 Jackson으로 파싱한다.
 */
@Repository
@RequiredArgsConstructor
// RLS GUC 는 트랜잭션 시작 시점에만 주입되는데 호출자 ReviewItemService(evidence 제외)와 MCP 추출
// 툴은 트랜잭션 경계를 만들지 않는다. 여기서 열지 않으면 세 갈래로 깨진다 — insertPending*: tenant_id
// NOT NULL 위반으로 추출 중단, lookup*: 0행이라 승인 항목이 중복 재생성, updateStatus: 0행인데
// 검수자에게는 성공으로 보고. 지우지 말 것.
@Transactional
public class JooqReviewItemRepository implements ReviewItemRepository {

  private final DSLContext dsl;

  private static final Table<?> T = table(name("graph_review_item"));

  /**
   * 목록 가시성 필터의 하위 질의 대상 — JooqDatasetVisibility#condition(DatasetVisibility) 이 쓰는 {@code "dataset"}
   * 이름 관례와 맞춘다.
   */
  private static final Table<?> DATASET_TABLE = table(name("dataset"));

  private static final Field<Long> DATASET_ROW_ID = field(name("dataset", "id"), Long.class);
  private static final Field<Long> ID = field(name("graph_review_item", "id"), Long.class);
  private static final Field<String> ITEM_TYPE =
      field(name("graph_review_item", "item_type"), String.class);
  private static final Field<String> STATUS =
      field(name("graph_review_item", "status"), String.class);
  private static final Field<Long> DATASET_ID =
      field(name("graph_review_item", "dataset_id"), Long.class);
  private static final Field<String> SIGNAL_TYPE =
      field(name("graph_review_item", "signal_type"), String.class);
  private static final Field<Double> SIGNAL_SCORE =
      field(name("graph_review_item", "signal_score"), Double.class);
  private static final Field<String> REASON =
      field(name("graph_review_item", "reason"), String.class);
  private static final Field<JSONB> PAYLOAD =
      field(name("graph_review_item", "payload"), JSONB.class);
  private static final Field<String> DEDUPE_KEY =
      field(name("graph_review_item", "dedupe_key"), String.class);
  private static final Field<Long> DECIDED_BY =
      field(name("graph_review_item", "decided_by"), Long.class);
  private static final Field<LocalDateTime> DECIDED_AT =
      field(name("graph_review_item", "decided_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> CREATED_AT =
      field(name("graph_review_item", "created_at"), LocalDateTime.class);
  // V101 에서 uq_graph_review_item 이 (tenant_id, item_type, dedupe_key) 로 접혔고, V134 에서
  // (tenant_id, item_type, dataset_id, dedupe_key) NULLS NOT DISTINCT 로 데이터셋 단위가 됐다.
  // ON CONFLICT 추론 대상을 새 인덱스와 맞추지 않으면 dedupe 가 런타임 오류로 터진다.
  // 값은 컬럼 DEFAULT(GUC app.tenant_id)가 채우므로 INSERT 에서는 세팅하지 않는다.
  private static final Field<Long> TENANT_ID =
      field(name("graph_review_item", "tenant_id"), Long.class);

  @Override
  public void upsertPending(
      String itemType,
      String dedupeKey,
      Long datasetId,
      String signalType,
      Double signalScore,
      String reason,
      String payloadJson) {
    dsl.insertInto(T)
        .set(ITEM_TYPE, itemType)
        .set(STATUS, "pending")
        .set(DATASET_ID, datasetId)
        .set(SIGNAL_TYPE, signalType)
        .set(SIGNAL_SCORE, signalScore)
        .set(REASON, reason)
        .set(PAYLOAD, JSONB.valueOf(payloadJson))
        .set(DEDUPE_KEY, dedupeKey)
        .onConflict(TENANT_ID, ITEM_TYPE, DATASET_ID, DEDUPE_KEY)
        .doNothing()
        .execute();
  }

  @Override
  public Optional<String> findDecisionStatus(
      String itemType, String dedupeKey, DatasetVisibility visibility) {
    return dsl.select(STATUS)
        .from(T)
        .where(ITEM_TYPE.eq(itemType).and(DEDUPE_KEY.eq(dedupeKey)).and(visibleItem(visibility)))
        .orderBy(
            DSL.when(STATUS.eq("pending"), 1).otherwise(0),
            DECIDED_AT.desc().nullsLast(),
            ID.desc())
        .limit(1)
        .fetchOptional(r -> r.get(STATUS));
  }

  /**
   * 조회자가 볼 수 있는 검수 항목 조건 — 출처 데이터셋이 보이는 항목 + 판정할 데이터셋이 없는 레거시 항목(dataset_id null). 목록·결정 조회가 같은 규칙을
   * 쓰도록 한 곳에 둔다.
   */
  private static Condition visibleItem(DatasetVisibility visibility) {
    return DATASET_ID
        .isNull()
        .or(
            DATASET_ID.in(
                select(DATASET_ROW_ID)
                    .from(DATASET_TABLE)
                    .where(JooqDatasetVisibility.condition(visibility))));
  }

  @Override
  public List<ReviewItemRecord> findByStatus(
      String status, String itemType, Integer offset, Integer limit, DatasetVisibility visibility) {
    Condition where = STATUS.eq(status);
    if (itemType != null) where = where.and(ITEM_TYPE.eq(itemType));
    // 보안 등급: 출처 데이터셋을 볼 수 있는 항목만(레거시 dataset_id null 은 판정 대상이 없어 유지). 페이지 경계가 맞도록 SQL 에서 거른다.
    where = where.and(visibleItem(visibility));
    var query =
        dsl.select(
                ID,
                ITEM_TYPE,
                STATUS,
                DATASET_ID,
                SIGNAL_TYPE,
                SIGNAL_SCORE,
                REASON,
                PAYLOAD,
                DECIDED_BY,
                DECIDED_AT,
                CREATED_AT)
            .from(T)
            .where(where)
            .orderBy(CREATED_AT, ID);
    if (limit != null) {
      return query.limit(limit).offset(offset == null ? 0 : offset).fetch(this::toRecord);
    }
    return query.fetch(this::toRecord);
  }

  @Override
  public Optional<ReviewItemRecord> findById(long id) {
    return dsl.select(
            ID,
            ITEM_TYPE,
            STATUS,
            DATASET_ID,
            SIGNAL_TYPE,
            SIGNAL_SCORE,
            REASON,
            PAYLOAD,
            DECIDED_BY,
            DECIDED_AT,
            CREATED_AT)
        .from(T)
        .where(ID.eq(id))
        .fetchOptional(this::toRecord);
  }

  @Override
  public void updateStatus(long id, String status, long decidedBy) {
    // pending 일 때만 바꾼다 — 조건 없이 쓰면 동시에 들어온 승인·거부가 서로의 결정을 덮어썼다(WD-428).
    // 정상 경로는 lockForDecision 으로 이미 직렬화돼 1행이다. 0행이면 다른 요청이 먼저 처리한 것이다.
    int updated =
        dsl.update(T)
            .set(STATUS, status)
            .set(DECIDED_BY, decidedBy)
            .set(DECIDED_AT, LocalDateTime.now())
            .where(ID.eq(id))
            .and(STATUS.eq("pending"))
            .execute();
    if (updated == 0) {
      throw new IllegalStateException("이미 다른 사용자가 처리한 항목입니다: " + id);
    }
  }

  @Override
  public void lockForDecision(long id) {
    dsl.select(ID).from(T).where(ID.eq(id)).forUpdate().fetch();
  }

  private ReviewItemRecord toRecord(org.jooq.Record r) {
    JSONB payload = r.get(PAYLOAD);
    return new ReviewItemRecord(
        r.get(ID),
        r.get(ITEM_TYPE),
        r.get(STATUS),
        r.get(DATASET_ID),
        r.get(SIGNAL_TYPE),
        r.get(SIGNAL_SCORE),
        r.get(REASON),
        payload == null ? null : payload.data(),
        r.get(DECIDED_BY),
        r.get(DECIDED_AT),
        r.get(CREATED_AT));
  }
}

package com.smartfirehub.synonymreview.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.synonymreview.dto.SynonymDecisionRecord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/**
 * synonym_decision 읽기/쓰기 리포지토리 — HITL 근접쌍 검수 대기열.
 *
 * <p>jOOQ 코드젠(생성 클래스)에 의존하지 않는 plain-SQL {@code name()} 스타일을 사용한다.
 * ({@link com.smartfirehub.graphingest.repository.GraphIngestRepository} 선례를 따름)
 */
@Repository
@RequiredArgsConstructor
public class SynonymDecisionRepository {

  private final DSLContext dsl;

  private static final Table<?> T = table(name("synonym_decision"));
  private static final Field<Long> ID = field(name("synonym_decision", "id"), Long.class);
  private static final Field<String> ENTITY_TYPE =
      field(name("synonym_decision", "entity_type"), String.class);
  private static final Field<String> NAME_A = field(name("synonym_decision", "name_a"), String.class);
  private static final Field<String> NAME_B = field(name("synonym_decision", "name_b"), String.class);
  private static final Field<String> STATUS = field(name("synonym_decision", "status"), String.class);
  private static final Field<Double> SIMILARITY =
      field(name("synonym_decision", "similarity"), Double.class);
  private static final Field<String> RATIONALE = field(name("synonym_decision", "rationale"), String.class);
  private static final Field<Long> DECIDED_BY = field(name("synonym_decision", "decided_by"), Long.class);
  private static final Field<LocalDateTime> DECIDED_AT =
      field(name("synonym_decision", "decided_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> CREATED_AT =
      field(name("synonym_decision", "created_at"), LocalDateTime.class);

  /** LLM이 "같다"고 판정한 근접쌍을 pending으로 upsert 등록한다. 이미 행이 존재하면(pending/approved/rejected 무관) 무시한다. */
  public void upsertPending(String entityType, String nameA, String nameB, Double similarity, String rationale) {
    dsl.insertInto(T)
        .set(ENTITY_TYPE, entityType)
        .set(NAME_A, nameA)
        .set(NAME_B, nameB)
        .set(STATUS, "pending")
        .set(SIMILARITY, similarity)
        .set(RATIONALE, rationale)
        .onConflict(ENTITY_TYPE, NAME_A, NAME_B)
        .doNothing()
        .execute();
  }

  /** 근접쌍의 기존 결정 상태 조회(없으면 Optional.empty — ingest는 이를 LLM 재호출 신호로 해석). */
  public Optional<String> findDecision(String entityType, String nameA, String nameB) {
    return dsl.select(STATUS)
        .from(T)
        .where(ENTITY_TYPE.eq(entityType).and(NAME_A.eq(nameA)).and(NAME_B.eq(nameB)))
        .fetchOptional(r -> r.get(STATUS));
  }

  /** 검수 대기 중(pending)인 전체 목록 — 등록순. */
  public List<SynonymDecisionRecord> findPending() {
    return dsl.select(ID, ENTITY_TYPE, NAME_A, NAME_B, STATUS, SIMILARITY, RATIONALE, DECIDED_BY, DECIDED_AT, CREATED_AT)
        .from(T)
        .where(STATUS.eq("pending"))
        .orderBy(CREATED_AT)
        .fetch(this::toRecord);
  }

  public Optional<SynonymDecisionRecord> findById(long id) {
    return dsl.select(ID, ENTITY_TYPE, NAME_A, NAME_B, STATUS, SIMILARITY, RATIONALE, DECIDED_BY, DECIDED_AT, CREATED_AT)
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

  private SynonymDecisionRecord toRecord(org.jooq.Record r) {
    return new SynonymDecisionRecord(
        r.get(ID), r.get(ENTITY_TYPE), r.get(NAME_A), r.get(NAME_B), r.get(STATUS),
        r.get(SIMILARITY), r.get(RATIONALE), r.get(DECIDED_BY), r.get(DECIDED_AT), r.get(CREATED_AT));
  }
}

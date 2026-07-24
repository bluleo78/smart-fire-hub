package com.smartfirehub.graphreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ReviewItemRepository 통합 테스트 — 실제 Postgres(smartfirehub_test). SynonymDecisionRepositoryTest 선례. */
class ReviewItemRepositoryTest extends IntegrationTestBase {

  @Autowired private ReviewItemRepository repo;
  @Autowired private DSLContext dsl;

  // 테스트 네임스페이스(dedupe_key LIKE 'Test%')를 매 테스트 전 정리한다.
  @BeforeEach
  void cleanup() {
    dsl.deleteFrom(table(name("graph_review_item")))
        .where(field(name("dedupe_key"), String.class).like("Test%"))
        .execute();
    dsl.execute("DELETE FROM \"user\" WHERE username LIKE 'testdecider%'");
  }

  @Test
  void upsertPending_thenFindDecisionStatus_returnsPending() {
    repo.upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "reason",
        "{\"entityType\":\"TestCause\",\"nameA\":\"a\",\"nameB\":\"b\"}");

    assertThat(repo.findDecisionStatus("synonym_merge", "TestCause|a|b")).contains("pending");
    assertThat(repo.findDecisionStatus("synonym_merge", "Test|none|none")).isEmpty();
  }

  @Test
  void upsertPending_duplicateKey_doesNothing() {
    repo.upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "first", "{}");
    repo.upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "second(무시)", "{}");

    var row = repo.findPending("synonym_merge").stream()
        .filter(r -> "TestCause|a|b".equals(dedupeKeyOf(r))).findFirst().orElseThrow();
    assertThat(row.reason()).isEqualTo("first");
  }

  @Test
  void findPending_filtersByItemType_andRoundTripsPayloadJson() {
    repo.upsertPending("property_normalization", "TestKey|피해액", 12L, "normalization_failure", null,
        "정규화 실패", "{\"entityKey\":\"3:화재\",\"propertyName\":\"피해액\",\"rawText\":\"약 3천만\"}");

    var props = repo.findPending("property_normalization").stream()
        .filter(r -> "TestKey|피해액".equals(dedupeKeyOf(r))).toList();
    assertThat(props).hasSize(1);
    assertThat(props.get(0).datasetId()).isEqualTo(12L);
    assertThat(props.get(0).payloadJson()).contains("\"rawText\"").contains("약 3천만");
    // 다른 타입 필터로는 안 나온다.
    assertThat(repo.findPending("synonym_merge").stream().anyMatch(r -> "TestKey|피해액".equals(dedupeKeyOf(r)))).isFalse();
  }

  @Test
  void updateStatus_approved_removesFromPending() {
    long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES ('testdecider','x','T','testdecider@example.com') RETURNING id")
        .get(0, Long.class);
    repo.upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "r", "{}");
    long id = repo.findPending("synonym_merge").stream()
        .filter(r -> "TestCause|a|b".equals(dedupeKeyOf(r))).findFirst().orElseThrow().id();

    repo.updateStatus(id, "approved", userId);

    assertThat(repo.findById(id).orElseThrow().status()).isEqualTo("approved");
    assertThat(repo.findById(id).orElseThrow().decidedBy()).isEqualTo(userId);
    assertThat(repo.findPending(null)).noneMatch(r -> r.id().equals(id));
    assertThat(repo.findDecisionStatus("synonym_merge", "TestCause|a|b")).contains("approved");
  }

  // dedupe_key는 Record에 노출하지 않으므로(내부 조회 키) 테스트에선 id로 DB에서 직접 읽어 비교한다.
  private String dedupeKeyOf(ReviewItemRecord r) {
    return dsl.select(field(name("dedupe_key"), String.class))
        .from(table(name("graph_review_item")))
        .where(field(name("id"), Long.class).eq(r.id()))
        .fetchOne(0, String.class);
  }
}

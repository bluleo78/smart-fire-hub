package com.smartfirehub.graphreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ReviewItemRepository 통합 테스트 — 실제 Postgres(smartfirehub_test). SynonymDecisionRepositoryTest 선례.
 *
 * <p>V102 로 graph_review_item 에 RLS 가 걸린 뒤로는 이 테스트가 직접 쏘는 raw dsl 삭제·조회도 정책의
 * 대상이다. GUC 는 트랜잭션이 열릴 때만 주입되므로 트랜잭션 밖 DELETE 는 0행이 되고, 남은 행이
 * decided_by FK 를 붙잡아 뒤이은 "user" 삭제가 FK 위반으로 터진다(실제로 그렇게 실패했다).
 * 그래서 정리·검증 조회만 트랜잭션으로 감싼다 — repo 호출은 그 자신의 트랜잭션 배선이 검증 대상이라 그대로 둔다.
 */
class ReviewItemRepositoryTest extends IntegrationTestBase {

  @Autowired private ReviewItemRepository repo;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  // 테스트 네임스페이스(dedupe_key LIKE 'Test%')를 매 테스트 전 정리한다.
  @BeforeEach
  void cleanup() {
    // graph_review_item 을 먼저 지워야 decided_by FK 때문에 "user" 삭제가 막히지 않는다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("graph_review_item")))
                .where(field(name("dedupe_key"), String.class).like("Test%"))
                .execute());
    // "user" 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 지울 수 있다.
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

    var row = repo.findByStatus("pending", "synonym_merge").stream()
        .filter(r -> "TestCause|a|b".equals(dedupeKeyOf(r))).findFirst().orElseThrow();
    assertThat(row.reason()).isEqualTo("first");
  }

  @Test
  void findByStatus_filtersByItemType_andRoundTripsPayloadJson() {
    repo.upsertPending("property_normalization", "TestKey|피해액", 12L, "normalization_failure", null,
        "정규화 실패", "{\"entityKey\":\"3:화재\",\"propertyName\":\"피해액\",\"rawText\":\"약 3천만\"}");

    var props = repo.findByStatus("pending", "property_normalization").stream()
        .filter(r -> "TestKey|피해액".equals(dedupeKeyOf(r))).toList();
    assertThat(props).hasSize(1);
    assertThat(props.get(0).datasetId()).isEqualTo(12L);
    assertThat(props.get(0).payloadJson()).contains("\"rawText\"").contains("약 3천만");
    // 다른 타입 필터로는 안 나온다.
    assertThat(repo.findByStatus("pending", "synonym_merge").stream().anyMatch(r -> "TestKey|피해액".equals(dedupeKeyOf(r)))).isFalse();
  }

  @Test
  void updateStatus_approved_removesFromPending() {
    long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES ('testdecider','x','T','testdecider@example.com') RETURNING id")
        .get(0, Long.class);
    repo.upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "r", "{}");
    long id = repo.findByStatus("pending", "synonym_merge").stream()
        .filter(r -> "TestCause|a|b".equals(dedupeKeyOf(r))).findFirst().orElseThrow().id();

    repo.updateStatus(id, "approved", userId);

    assertThat(repo.findById(id).orElseThrow().status()).isEqualTo("approved");
    assertThat(repo.findById(id).orElseThrow().decidedBy()).isEqualTo(userId);
    assertThat(repo.findByStatus("pending", null)).noneMatch(r -> r.id().equals(id));
    assertThat(repo.findDecisionStatus("synonym_merge", "TestCause|a|b")).contains("approved");
    // status 필터가 실제로 동작한다 — 예전에는 'pending' 하드코딩이라 approved 조회가 불가능했다(#318).
    assertThat(repo.findByStatus("approved", null)).anyMatch(r -> r.id().equals(id));
    assertThat(repo.findByStatus("rejected", null)).noneMatch(r -> r.id().equals(id));
  }

  // dedupe_key는 Record에 노출하지 않으므로(내부 조회 키) 테스트에선 id로 DB에서 직접 읽어 비교한다.
  private String dedupeKeyOf(ReviewItemRecord r) {
    // RLS 대상 조회 — 트랜잭션(=GUC) 안에서 읽어야 한다.
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.select(field(name("dedupe_key"), String.class))
                .from(table(name("graph_review_item")))
                .where(field(name("id"), Long.class).eq(r.id()))
                .fetchOne(0, String.class));
  }
}

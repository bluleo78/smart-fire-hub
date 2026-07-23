package com.smartfirehub.synonymreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.synonymreview.repository.SynonymDecisionRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** SynonymDecisionRepository 통합 테스트 — 실제 Postgres(smartfirehub_test) 대상. */
class SynonymDecisionRepositoryTest extends IntegrationTestBase {

  @Autowired private SynonymDecisionRepository repo;
  @Autowired private DSLContext dsl;

  // 테스트 네임스페이스(entity_type='TestCause')를 매 테스트 전 정리해 누적 행으로 인한 실패를 방지한다.
  @BeforeEach
  void cleanupTestNamespace() {
    dsl.deleteFrom(table(name("synonym_decision")))
        .where(field(name("entity_type"), String.class).eq("TestCause"))
        .execute();
    dsl.execute("DELETE FROM \"user\" WHERE username LIKE 'testdecider%'");
  }

  @Test
  void upsertPending_thenFindDecision_returnsPending() {
    repo.upsertPending("TestCause", "전기적 요인", "분전반의 누전", 0.7, "동의어로 보임");

    assertThat(repo.findDecision("TestCause", "전기적 요인", "분전반의 누전")).contains("pending");
    assertThat(repo.findDecision("TestCause", "없음", "없음2")).isEmpty();
  }

  @Test
  void upsertPending_duplicatePair_doesNothing() {
    repo.upsertPending("TestCause", "전기적 요인", "분전반의 누전", 0.7, "첫 rationale");
    repo.upsertPending("TestCause", "전기적 요인", "분전반의 누전", 0.7, "두번째 rationale — 무시되어야 함");

    var pending = repo.findPending();
    var row = pending.stream().filter(r -> "TestCause".equals(r.entityType())).findFirst().orElseThrow();
    assertThat(row.rationale()).isEqualTo("첫 rationale");
  }

  @Test
  void updateStatus_approved_removesFromPendingList() {
    // 테스트 사용자 생성
    long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES ('testdecider','x','Test Decider','testdecider@example.com') RETURNING id")
        .get(0, Long.class);

    repo.upsertPending("TestCause", "전기적 요인", "분전반의 누전", 0.7, "rationale");
    long id = repo.findPending().stream()
        .filter(r -> "TestCause".equals(r.entityType())).findFirst().orElseThrow().id();

    repo.updateStatus(id, "approved", userId);

    assertThat(repo.findById(id).orElseThrow().status()).isEqualTo("approved");
    assertThat(repo.findById(id).orElseThrow().decidedBy()).isEqualTo(userId);
    assertThat(repo.findPending()).noneMatch(r -> r.id().equals(id));
    assertThat(repo.findDecision("TestCause", "전기적 요인", "분전반의 누전")).contains("approved");
  }
}

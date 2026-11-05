package com.smartfirehub.graphreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ReviewItemRepository 통합 테스트 — 실제 Postgres(smartfirehub_test). SynonymDecisionRepositoryTest 선례.
 *
 * <p>V102 로 graph_review_item 에 RLS 가 걸린 뒤로는 이 테스트가 직접 쏘는 raw dsl 삭제·조회도 정책의 대상이다. GUC 는 트랜잭션이 열릴
 * 때만 주입되므로 트랜잭션 밖 DELETE 는 0행이 되고, 남은 행이 decided_by FK 를 붙잡아 뒤이은 "user" 삭제가 FK 위반으로 터진다(실제로 그렇게
 * 실패했다). 그래서 정리·검증 조회만 트랜잭션으로 감싼다 — repo 호출은 그 자신의 트랜잭션 배선이 검증 대상이라 그대로 둔다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485).
 */
abstract class ReviewItemRepositoryContractTest extends IntegrationTestBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract ReviewItemRepository repository();

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  // 테스트 네임스페이스(dedupe_key LIKE 'Test%')를 매 테스트 전 정리한다.
  @BeforeEach
  void cleanup() {
    cleanupFixtures();
  }

  // ── 픽스처 seam(WD-487) ── 데이터 넣기·지우기·원시 조회를 아래 protected 메서드로 모은다. 기본 구현은 PG(지금 코드 그대로)이고,
  // Tibero 하위 클래스가 같은 의미를 Tibero DataSource 위에서 다시 구현한다. 테스트 본문의 단언은 이 seam 과 무관하다.

  /** 이 테스트 네임스페이스의 검수 항목·결정자 사용자를 지운다. */
  protected void cleanupFixtures() {
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

  /**
   * 기본 테넌트 컨텍스트 + 트랜잭션 안에서 작업을 실행한다 — 동시 결정 시험에서 잠금과 갱신이 한 트랜잭션을 공유해야 해서 둔다. Tibero 하위 클래스는 Tibero
   * 트랜잭션으로 연다(PG 트랜잭션으로 열면 Tibero 잠금이 호출마다 풀린다).
   */
  protected <T> T inTenantTransaction(Supplier<T> work) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, DEFAULT_TEST_TENANT_ID, work);
  }

  @Test
  void upsertPending_thenFindDecisionStatus_returnsPending() {
    repository()
        .upsertPending(
            "synonym_merge",
            "TestCause|a|b",
            null,
            "similarity",
            0.7,
            "reason",
            "{\"entityType\":\"TestCause\",\"nameA\":\"a\",\"nameB\":\"b\"}");

    assertThat(
            repository()
                .findDecisionStatus(
                    "synonym_merge", "TestCause|a|b", DatasetVisibility.UNRESTRICTED))
        .contains("pending");
    assertThat(
            repository()
                .findDecisionStatus(
                    "synonym_merge", "Test|none|none", DatasetVisibility.UNRESTRICTED))
        .isEmpty();
  }

  @Test
  void upsertPending_duplicateKey_doesNothing() {
    repository()
        .upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "first", "{}");
    repository()
        .upsertPending(
            "synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "second(무시)", "{}");

    var row =
        repository()
            .findByStatus("pending", "synonym_merge", null, null, DatasetVisibility.UNRESTRICTED)
            .stream()
            .filter(r -> "TestCause|a|b".equals(dedupeKeyOf(r)))
            .findFirst()
            .orElseThrow();
    assertThat(row.reason()).isEqualTo("first");
  }

  @Test
  void findByStatus_filtersByItemType_andRoundTripsPayloadJson() {
    repository()
        .upsertPending(
            "property_normalization",
            "TestKey|피해액",
            // 출처 데이터셋 가시성 필터(보안 등급)는 실제로 있는 데이터셋 행을 요구한다 — 이 테스트의 관심사는 타입 필터·payload 왕복이라
            // 데이터셋 없는(레거시) 항목으로 둔다. 가시성 필터 자체는 ReviewItemVisibilityTest 가 고정한다.
            null,
            "normalization_failure",
            null,
            "정규화 실패",
            "{\"entityKey\":\"3:화재\",\"propertyName\":\"피해액\",\"rawText\":\"약 3천만\"}");

    var props =
        repository()
            .findByStatus(
                "pending", "property_normalization", null, null, DatasetVisibility.UNRESTRICTED)
            .stream()
            .filter(r -> "TestKey|피해액".equals(dedupeKeyOf(r)))
            .toList();
    assertThat(props).hasSize(1);
    assertThat(props.get(0).datasetId()).isNull();
    assertThat(props.get(0).payloadJson()).contains("\"rawText\"").contains("약 3천만");
    // 다른 타입 필터로는 안 나온다.
    assertThat(
            repository()
                .findByStatus(
                    "pending", "synonym_merge", null, null, DatasetVisibility.UNRESTRICTED)
                .stream()
                .anyMatch(r -> "TestKey|피해액".equals(dedupeKeyOf(r))))
        .isFalse();
  }

  @Test
  void updateStatus_approved_removesFromPending() {
    long userId = insertDecider();
    repository()
        .upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "r", "{}");
    long id =
        repository()
            .findByStatus("pending", "synonym_merge", null, null, DatasetVisibility.UNRESTRICTED)
            .stream()
            .filter(r -> "TestCause|a|b".equals(dedupeKeyOf(r)))
            .findFirst()
            .orElseThrow()
            .id();

    repository().updateStatus(id, "approved", userId);

    assertThat(repository().findById(id).orElseThrow().status()).isEqualTo("approved");
    assertThat(repository().findById(id).orElseThrow().decidedBy()).isEqualTo(userId);
    assertThat(
            repository().findByStatus("pending", null, null, null, DatasetVisibility.UNRESTRICTED))
        .noneMatch(r -> r.id().equals(id));
    assertThat(
            repository()
                .findDecisionStatus(
                    "synonym_merge", "TestCause|a|b", DatasetVisibility.UNRESTRICTED))
        .contains("approved");
    // status 필터가 실제로 동작한다 — 예전에는 'pending' 하드코딩이라 approved 조회가 불가능했다(#318).
    assertThat(
            repository().findByStatus("approved", null, null, null, DatasetVisibility.UNRESTRICTED))
        .anyMatch(r -> r.id().equals(id));
    assertThat(
            repository().findByStatus("rejected", null, null, null, DatasetVisibility.UNRESTRICTED))
        .noneMatch(r -> r.id().equals(id));
  }

  /**
   * 한 행 안의 시각 기준이 같다 — created_at 은 DB DEFAULT 가, decided_at 은 앱이 {@code LocalDateTime.now()} 로 쓴다.
   * PG 는 두 값 모두 세션(=JVM) 시간대 벽시계다. Tibero DDL 의 DEFAULT 가 UTC 벽시계면 KST JVM 에서 9시간 어긋나므로 그 회귀를 잡는다(몇
   * 분 여유).
   */
  @Test
  void createdAtAndDecidedAt_useSameClockConvention() {
    long userId = insertDecider();
    long id = insertPendingSynonym();
    repository().updateStatus(id, "approved", userId);

    ReviewItemRecord row = repository().findById(id).orElseThrow();
    assertThat(row.createdAt()).isNotNull();
    assertThat(row.decidedAt()).isNotNull();
    assertThat(java.time.Duration.between(row.createdAt(), row.decidedAt()).abs())
        .as("created_at(DEFAULT)·decided_at(앱) 이 같은 시간대 기준이어야 한다")
        .isLessThan(java.time.Duration.ofMinutes(5));
  }

  // ── 동시 결정(WD-428) ── 승인이 그래프를 바꾸는 사이에 들어온 거부가 pending 으로 보고 「거부했습니다」를 돌려준 뒤, 승인이 approved 로
  // 덮어쓰던 문제. 상태 갱신은 pending 일 때만 되고, 결정 중에는 행을 잠가 뒤 요청이 앞 결정의 결과를 보게 한다.

  @Test
  void updateStatus_alreadyDecided_throwsAndKeepsFirstDecision() {
    long userId = insertDecider();
    long id = insertPendingSynonym();

    repository().updateStatus(id, "approved", userId);

    // 이미 approved 인 항목을 rejected 로 덮어쓰지 않는다 — 예전에는 조건 없는 UPDATE 라 성공했다.
    assertThatThrownBy(() -> repository().updateStatus(id, "rejected", userId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("이미 다른 사용자가 처리한 항목");
    assertThat(repository().findById(id).orElseThrow().status()).isEqualTo("approved");
  }

  @Test
  void lockForDecision_blocksConcurrentDecisionUntilFirstCommits() throws Exception {
    long userId = insertDecider();
    long id = insertPendingSynonym();
    CountDownLatch firstLocked = new CountDownLatch(1);

    // 첫 결정(승인): 행을 잠근 채 그래프 변경에 걸리는 시간만큼 머문 뒤 approved 로 바꾸고 커밋한다.
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                inTenantTransaction(
                    () -> {
                      repository().lockForDecision(id);
                      firstLocked.countDown();
                      sleep(600);
                      repository().updateStatus(id, "approved", userId);
                      return null;
                    }));
    assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

    // 두 번째 결정(거부): 잠금에서 기다렸다가, 앞 결정이 커밋된 뒤의 상태(approved)를 본다 — pending 으로 보고 성공하지 않는다.
    long started = System.nanoTime();
    String seen =
        CompletableFuture.supplyAsync(
                () ->
                    inTenantTransaction(
                        () -> {
                          repository().lockForDecision(id);
                          return repository().findById(id).orElseThrow().status();
                        }))
            .get(15, TimeUnit.SECONDS);
    long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    first.get(15, TimeUnit.SECONDS);

    assertThat(seen).isEqualTo("approved");
    assertThat(waitedMs).isGreaterThanOrEqualTo(300);
  }

  /** 결정자 사용자 'testdecider' 를 넣고 id 를 돌려준다. */
  protected long insertDecider() {
    return dsl.fetchOne(
            "INSERT INTO \"user\"(username, password, name, email) VALUES ('testdecider','x','T','testdecider@example.com') RETURNING id")
        .get(0, Long.class);
  }

  private long insertPendingSynonym() {
    repository()
        .upsertPending("synonym_merge", "TestCause|a|b", null, "similarity", 0.7, "r", "{}");
    return repository()
        .findByStatus("pending", "synonym_merge", null, null, DatasetVisibility.UNRESTRICTED)
        .stream()
        .filter(r -> "TestCause|a|b".equals(dedupeKeyOf(r)))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // ── page/size(opt-in, #422) ── limit/offset 이 실제로 행 수를 제한하고, orderBy(CREATED_AT, ID) 2차
  // 정렬키 덕에 동시각 삽입 행에서도 경계 없이 전량을 커버한다(중복·누락 없음)를 검증한다.
  @Test
  void findByStatus_withLimit_boundsRowCountAndCoversAllPagesWithoutOverlap() {
    // 공유 test DB에는 다른 테스트가 남긴 pending 행이 섞여 있을 수 있다(참고: 공유 test DB 오염 함정).
    // item_type을 이 테스트 전용 값으로 써서 실제 4종(synonym_merge 등)과 절대 겹치지 않게 격리한다 —
    // repo.findByStatus 자체는 item_type 값을 검증하지 않으므로(허용값 검증은 서비스 계층) 가능하다.
    String isolatedType = "test_pagination_marker";
    for (int i = 0; i < 5; i++) {
      repository().upsertPending(isolatedType, "TestPage|" + i, null, "similarity", 0.5, "r", "{}");
    }

    var page0 =
        repository().findByStatus("pending", isolatedType, 0, 2, DatasetVisibility.UNRESTRICTED);
    assertThat(page0).hasSize(2);
    var page1 =
        repository().findByStatus("pending", isolatedType, 2, 2, DatasetVisibility.UNRESTRICTED);
    assertThat(page1).hasSize(2);
    var page2 =
        repository().findByStatus("pending", isolatedType, 4, 2, DatasetVisibility.UNRESTRICTED);
    // 마지막 페이지는 남은 1건만 — 요청한 size보다 적게 와도 됨을 확인.
    assertThat(page2).hasSize(1);

    var ids0 = page0.stream().map(ReviewItemRecord::id).toList();
    var ids1 = page1.stream().map(ReviewItemRecord::id).toList();
    var ids2 = page2.stream().map(ReviewItemRecord::id).toList();
    // 세 페이지를 합치면 limit 없이 조회한 전체와 정확히 일치해야 한다(중복도 누락도 없이).
    var all =
        repository()
            .findByStatus("pending", isolatedType, null, null, DatasetVisibility.UNRESTRICTED)
            .stream()
            .map(ReviewItemRecord::id)
            .toList();
    var paged = new java.util.ArrayList<Long>();
    paged.addAll(ids0);
    paged.addAll(ids1);
    paged.addAll(ids2);
    assertThat(paged).hasSize(all.size());
    assertThat(new java.util.HashSet<>(paged)).isEqualTo(new java.util.HashSet<>(all));
  }

  // dedupe_key는 Record에 노출하지 않으므로(내부 조회 키) 테스트에선 id로 DB에서 직접 읽어 비교한다.
  private String dedupeKeyOf(ReviewItemRecord r) {
    return dedupeKeyOf(r.id());
  }

  /** id 의 dedupe_key 원시 조회(픽스처 seam). */
  protected String dedupeKeyOf(long id) {
    // RLS 대상 조회 — 트랜잭션(=GUC) 안에서 읽어야 한다.
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.select(field(name("dedupe_key"), String.class))
                .from(table(name("graph_review_item")))
                .where(field(name("id"), Long.class).eq(id))
                .fetchOne(0, String.class));
  }
}

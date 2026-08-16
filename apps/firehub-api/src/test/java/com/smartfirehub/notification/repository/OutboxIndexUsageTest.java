package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V108 이 {@code notification_outbox} 에 만든 테넌트 선행 인덱스의 <b>형태</b>를 고정한다.
 *
 * <p>단순히 이름 존재만 확인하면 공허하다 — 실측(EXPLAIN)에서 확인한 "왜 이 컬럼 순서인가"가
 * 다음 사람이 인덱스를 손대도 재측정 없이 판단할 수 있게, {@code indexdef} 로 컬럼 순서까지 못박는다.
 *
 * <p>역방향 자기검증({@link #onlyExpectedIndexesExistOnNotificationOutbox()})은
 * {@code TenantSchemaConformanceTest} 의 {@code containsExactlyInAnyOrderElementsOf} 패턴을
 * 그대로 따른다 — 인덱스 전수를 기대 집합과 정확히 맞춰야, 나중에 누가 인덱스를 하나 지우거나
 * 몰래 더 추가해도 이 테스트가 빨개진다.
 */
class OutboxIndexUsageTest extends IntegrationTestBase {

  /**
   * V108 이후 {@code notification_outbox} 에 존재해야 하는 인덱스 전체 집합(제약이 만드는 인덱스
   * {@code uk_outbox_idempotency}, {@code notification_outbox_pkey} 포함). {@code
   * idx_outbox_pending_due} 는 V108 이 대체·삭제했으므로 여기 없다.
   */
  private static final Set<String> EXPECTED_INDEXES =
      Set.of(
          "notification_outbox_pkey",
          "uk_outbox_idempotency",
          "idx_outbox_correlation",
          "idx_outbox_recipient",
          "idx_outbox_zombie",
          "idx_outbox_pending_tenant_due",
          "idx_outbox_status_tenant");

  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("idx_outbox_pending_tenant_due 는 tenant_id 를 선행 컬럼으로 갖는 PENDING 부분 인덱스다")
  void pendingTenantDueIndexLeadsWithTenantId() {
    String indexDef = indexDefOf("idx_outbox_pending_tenant_due");

    // claimDue 는 tenant_id 등치 + next_attempt_at 범위/정렬을 함께 쓴다 — tenant_id 가 앞서야
    // 인덱스 순서가 그대로 정렬 결과가 된다(실측: Index Scan, 별도 Sort 없음).
    assertThat(indexDef)
        .as("컬럼 순서가 (tenant_id, next_attempt_at) 여야 한다: %s", indexDef)
        .containsPattern("\\(tenant_id, next_attempt_at\\)");
    // countPendingByChannel 이 힙을 보지 않도록 channel_type 을 INCLUDE 로 실어야 한다
    // (실측: VACUUM 후 Heap Fetches: 0).
    assertThat(indexDef).as("channel_type 을 INCLUDE 해야 한다: %s", indexDef).contains("INCLUDE (channel_type)");
    assertThat(indexDef)
        .as("PENDING 부분 인덱스여야 한다: %s", indexDef)
        .contains("WHERE ((status)::text = 'PENDING'::text)");
  }

  @Test
  @DisplayName("idx_outbox_status_tenant 는 status 를 선행 컬럼으로 갖는다 (outbox_tenant_ids 전용)")
  void statusTenantIndexLeadsWithStatus() {
    String indexDef = indexDefOf("idx_outbox_status_tenant");

    // outbox_tenant_ids 는 WHERE status = ANY(...) 만 걸고 tenant_id 는 SELECT DISTINCT 대상일
    // 뿐이라, status 가 앞서야 Index Cond 로 좁혀지고 tenant_id 는 index-only 로 딸려온다.
    assertThat(indexDef)
        .as("컬럼 순서가 (status, tenant_id) 여야 한다: %s", indexDef)
        .containsPattern("\\(status, tenant_id\\)");
  }

  @Test
  @DisplayName("idx_outbox_pending_due 는 V108 이 대체해 더 이상 존재하지 않는다")
  void oldPendingDueIndexIsGone() {
    Integer count =
        dsl.fetchOne(
                "select count(*)::int from pg_indexes"
                    + " where schemaname='public' and tablename='notification_outbox'"
                    + " and indexname='idx_outbox_pending_due'")
            .get(0, Integer.class);
    assertThat(count).as("idx_outbox_pending_due 가 아직 남아 있다").isZero();
  }

  @Test
  @DisplayName("notification_outbox 의 인덱스 전수가 기대 집합과 정확히 일치한다 (역방향 자기검증)")
  void onlyExpectedIndexesExistOnNotificationOutbox() {
    List<String> actual =
        dsl.fetch(
                "select indexname from pg_indexes"
                    + " where schemaname='public' and tablename='notification_outbox'")
            .stream()
            .map(r -> r.get("indexname", String.class))
            .collect(Collectors.toList());

    assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_INDEXES);
  }

  // EXPLAIN 계획 자체(Index Only Scan 등장 여부)는 단언하지 않는다 — 옵티마이저는 test DB 의
  // 작은 행 수(PENDING 61행)에서 통계가 조금만 바뀌어도 seq scan 을 고를 수 있어 불안정하다.
  // 실측(EXPLAIN ANALYZE)은 태스크 보고서에 원문으로 남겼고, 여기서는 재현 가능한 카탈로그
  // 형태(indexdef)만 고정한다.

  private String indexDefOf(String indexName) {
    var row =
        dsl.fetchOne(
            "select indexdef from pg_indexes"
                + " where schemaname='public' and tablename='notification_outbox' and indexname=?",
            indexName);
    assertThat(row).as("%s 인덱스가 없다", indexName).isNotNull();
    return row.get("indexdef", String.class);
  }
}

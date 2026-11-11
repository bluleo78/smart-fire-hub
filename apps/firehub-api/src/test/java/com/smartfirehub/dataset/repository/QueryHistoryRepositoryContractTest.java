package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.dataset.dto.QueryHistoryResponse;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QueryHistoryRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). query_history 는 테넌트 정책 아래 있다. success
 * 는 PG boolean ↔ Tibero NUMBER(1) 이라 왕복을 고정한다.
 */
public abstract class QueryHistoryRepositoryContractTest extends TenantPairContractBase {

  protected abstract QueryHistoryRepository repository();

  private long userA;

  @BeforeEach
  void setUpExtra() {
    userA = fx.createUser(prefix + "-u", null);
  }

  /** 호출마다 별도 트랜잭션이라 executed_at(트랜잭션 시각)이 달라 최신순이 정해진다. */
  private void save(long ds, String sql, boolean success, String error) {
    inA(
        () -> {
          repository().save(ds, userA, sql, "SELECT", 7, 123L, success, error);
          return null;
        });
  }

  @Test
  @DisplayName("save 한 이력이 필드 그대로(성공·실패 boolean 왕복 포함) 읽힌다")
  void save_roundTrip() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    save(ds, "SELECT 1 /* 한글 */", true, null);
    save(ds, "DROP x", false, "오류 발생: " + "x".repeat(2000));

    List<QueryHistoryResponse> rows = inA(() -> repository().findByDatasetId(ds, 0, 10));

    assertThat(rows).hasSize(2);
    QueryHistoryResponse failed = rows.get(0);
    QueryHistoryResponse ok = rows.get(1);
    assertThat(failed.sql()).isEqualTo("DROP x");
    assertThat(failed.success()).isFalse();
    assertThat(failed.error()).isEqualTo("오류 발생: " + "x".repeat(2000));
    assertThat(ok.sql()).isEqualTo("SELECT 1 /* 한글 */");
    assertThat(ok.success()).isTrue();
    assertThat(ok.error()).isNull();
    assertThat(ok.queryType()).isEqualTo("SELECT");
    assertThat(ok.affectedRows()).isEqualTo(7);
    assertThat(ok.executionTimeMs()).isEqualTo(123L);
    assertThat(ok.id()).isNotNull();
    assertThat(ok.executedAt()).isCloseTo(LocalDateTime.now(), within(3, ChronoUnit.MINUTES));
  }

  @Test
  @DisplayName("빈 문자열 오류 메시지 — PG 는 그대로, Tibero 는 NULL 로 돌아온다")
  void save_emptyError() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    save(ds, "SELECT 1", false, "");

    QueryHistoryResponse row = inA(() -> repository().findByDatasetId(ds, 0, 10)).get(0);

    assertThat(row.error()).isEqualTo(emptyStringIsNull() ? null : "");
  }

  @Test
  @DisplayName("findByDatasetId 는 최신순으로 페이지(2쪽 포함)를 나누고 countByDatasetId 가 전체 건수를 센다")
  void findByDatasetId_pagesNewestFirst() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    for (int i = 1; i <= 5; i++) {
      save(ds, "q" + i, i % 2 == 0, null);
    }
    save(other, "other-q", true, null);

    assertThat(inA(() -> repository().findByDatasetId(ds, 0, 2)))
        .extracting(QueryHistoryResponse::sql)
        .containsExactly("q5", "q4");
    assertThat(inA(() -> repository().findByDatasetId(ds, 1, 2)))
        .extracting(QueryHistoryResponse::sql)
        .containsExactly("q3", "q2");
    assertThat(inA(() -> repository().findByDatasetId(ds, 2, 2)))
        .extracting(QueryHistoryResponse::sql)
        .containsExactly("q1");
    assertThat(inA(() -> repository().findByDatasetId(ds, 3, 2))).isEmpty();
    assertThat(inA(() -> repository().countByDatasetId(ds))).isEqualTo(5L);
    assertThat(inA(() -> repository().countByDatasetId(other))).isEqualTo(1L);
    assertThat(inA(() -> repository().countByDatasetId(-1L))).isZero();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 A 데이터셋의 이력이 0건")
  void otherTenantIsBlocked() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    save(ds, "q", true, null);

    assertThat(fx.inTenant(tenantB, () -> repository().findByDatasetId(ds, 0, 10))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().countByDatasetId(ds))).isZero();
  }
}

package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.repository.AnalyticsQueryRunRepository.Run;
import com.smartfirehub.global.platformdb.mybatis.LongTextAssertions;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AnalyticsQueryRunRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-710).
 *
 * <p>analytics_query_run 은 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트로 감싼다. 보존 기간(1시간)은 DB 시계로 판정하므로 생성 시각은
 * 픽스처가 DB 시계 기준으로 옮기고, 경계(60분)에서 멀리 떨어진 30분·2시간만 쓴다.
 */
public abstract class AnalyticsQueryRunRepositoryContractTest extends TenantPairContractBase {

  protected abstract AnalyticsQueryRunRepository repository();

  /** 실행 기록 주인. */
  private long owner;

  /** 다른 사용자 — 남의 기록이 안 보이는지 확인한다. */
  private long other;

  @BeforeEach
  void createUsers() {
    owner = fx.createUser(prefix + "-owner", null);
    other = fx.createUser(prefix + "-other", null);
  }

  private UUID insertA(long userId, String sql, int maxRows) {
    return inA(() -> repository().insert(userId, sql, maxRows));
  }

  @Test
  @DisplayName("insert·findOwned — 자바가 만든 id 로 저장되고 주인이 SQL 원문(긴 한글)·행 상한을 그대로 읽는다")
  void insert_thenFindOwnedRoundTrips() {
    String sql = "SELECT '한글 값' AS v FROM dual -- " + "가".repeat(3000);
    UUID id = insertA(owner, sql, 500);

    assertThat(id).isNotNull();
    assertThat(fx.analyticsQueryRunExists(id)).isTrue();
    assertThat(inA(() -> repository().findOwned(id, owner))).contains(new Run(id, sql, 500));
  }

  /** SQL 본문은 잘리면 안 된다 — Tibero 도 sql_text 가 CLOB 라 65532 바이트를 넘어도 원문 그대로여야 한다(WD-740). */
  @Test
  @DisplayName("sql_text_가_65532바이트를_넘어도_저장되고_그대로_읽힌다")
  void sql_text_가_65532바이트를_넘어도_저장되고_그대로_읽힌다() {
    String sql = LongTextAssertions.koreanSql70Kb("-- 끝");
    UUID id = insertA(owner, sql, 500);

    assertThat(inA(() -> repository().findOwned(id, owner)).orElseThrow().sqlText()).isEqualTo(sql);
    assertThat(inA(() -> repository().findOwned(id, owner))).contains(new Run(id, sql, 500));
  }

  @Test
  @DisplayName("insert — 부를 때마다 서로 다른 id 를 돌려준다")
  void insert_distinctIds() {
    UUID a = insertA(owner, "SELECT 1", 10);
    UUID b = insertA(owner, "SELECT 1", 10);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("findOwned — 다른 사용자·다른 테넌트·없는 id 는 모두 empty")
  void findOwned_hidesOthers() {
    UUID id = insertA(owner, "SELECT 1", 10);

    assertThat(inA(() -> repository().findOwned(id, other))).isEmpty();
    assertThat(inB(() -> repository().findOwned(id, owner))).isEmpty();
    assertThat(inA(() -> repository().findOwned(UUID.randomUUID(), owner))).isEmpty();
  }

  @Test
  @DisplayName("findOwned — DB 시계로 30분 전 기록은 보이고 2시간 전(보존 기간 밖) 기록은 empty")
  void findOwned_respectsRetentionWindow() {
    UUID fresh = insertA(owner, "SELECT 'fresh'", 10);
    UUID stale = insertA(owner, "SELECT 'stale'", 10);
    fx.ageAnalyticsQueryRun(fresh, 30);
    fx.ageAnalyticsQueryRun(stale, 120);

    assertThat(inA(() -> repository().findOwned(fresh, owner)))
        .contains(new Run(fresh, "SELECT 'fresh'", 10));
    assertThat(inA(() -> repository().findOwned(stale, owner))).isEmpty();
  }

  @Test
  @DisplayName("deleteExpired — 현재 테넌트의 보존 기간 지난 기록만 지우고 지운 수를 돌려준다(다른 테넌트·새 기록은 남는다)")
  void deleteExpired_onlyCurrentTenantExpired() {
    UUID fresh = insertA(owner, "SELECT 1", 10);
    UUID staleMine = insertA(owner, "SELECT 2", 10);
    UUID staleOther = insertA(other, "SELECT 3", 10);
    UUID staleB = inB(() -> repository().insert(owner, "SELECT 4", 10));
    fx.ageAnalyticsQueryRun(fresh, 30);
    fx.ageAnalyticsQueryRun(staleMine, 120);
    fx.ageAnalyticsQueryRun(staleOther, 120);
    fx.ageAnalyticsQueryRun(staleB, 120);

    assertThat(inA(() -> repository().deleteExpired())).isEqualTo(2);

    assertThat(fx.analyticsQueryRunExists(fresh)).isTrue();
    assertThat(fx.analyticsQueryRunExists(staleMine)).isFalse();
    assertThat(fx.analyticsQueryRunExists(staleOther)).isFalse();
    assertThat(fx.analyticsQueryRunExists(staleB)).as("다른 테넌트의 만료 기록은 남는다").isTrue();
    assertThat(inA(() -> repository().deleteExpired())).isZero();
    assertThat(inB(() -> repository().deleteExpired())).isEqualTo(1);
  }
}

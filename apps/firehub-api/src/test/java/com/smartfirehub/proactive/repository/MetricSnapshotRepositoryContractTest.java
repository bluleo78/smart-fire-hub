package com.smartfirehub.proactive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MetricSnapshotRepository 계약 테스트 — 공개 메서드 4 개 전부(WD-485·WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis
 * 는 Tibero 실서버(두 계정 RLS)다. 선행 데이터는 {@code fx} 픽스처로 만든다.
 *
 * <p>시각은 jOOQ 구현과 같이 자바 {@code ProactiveTime.nowUtc()} 기준이므로 테스트도 같은 UTC 벽시계를 쓴다.
 */
abstract class MetricSnapshotRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract MetricSnapshotRepository repository();

  private long jobA;
  private long jobB;

  @BeforeEach
  void setUpJobs() {
    long userA = fx.createUser(prefix + "-ua", null);
    long userB = fx.createUser(prefix + "-ub", null);
    jobA = fx.createProactiveJob(tenantA, userA, "Metric Test Job A");
    jobB = fx.createProactiveJob(tenantB, userB, "Metric Test Job B");
  }

  private void saveA(long job, String metric, double value, LocalDateTime at) {
    runA(() -> repository().save(job, metric, value, at));
  }

  @Test
  @DisplayName("저장 후 findRecent 는 최근 순이고 소수·음수 값이 그대로다")
  void save_and_findRecent_returnsInOrder() {
    LocalDateTime now = ProactiveTime.nowUtc();
    saveA(jobA, "m1", 100.5, now.minusHours(2));
    saveA(jobA, "m1", -200.25, now.minusHours(1));
    saveA(jobA, "m1", 300.125, now);

    List<MetricSnapshotRepository.MetricSnapshot> snapshots =
        inA(() -> repository().findRecent(jobA, "m1", 14));

    assertThat(snapshots).hasSize(3);
    assertThat(snapshots)
        .extracting(MetricSnapshotRepository.MetricSnapshot::value)
        .containsExactly(300.125, -200.25, 100.5);
    var first = snapshots.get(0);
    assertThat(first.id()).isNotNull();
    assertThat(first.jobId()).isEqualTo(jobA);
    assertThat(first.metricId()).isEqualTo("m1");
    assertThat(first.collectedAt()).isEqualToIgnoringNanos(now);
  }

  @Test
  @DisplayName("findRecent 는 days 일 이전 스냅샷과 다른 metricId 를 거른다")
  void findRecent_filtersByDaysAndMetric() {
    LocalDateTime now = ProactiveTime.nowUtc();
    saveA(jobA, "m1", 1.0, now.minusDays(10));
    saveA(jobA, "m1", 2.0, now.minusDays(1));
    saveA(jobA, "other", 9.0, now.minusDays(1));

    assertThat(inA(() -> repository().findRecent(jobA, "m1", 3)))
        .extracting(MetricSnapshotRepository.MetricSnapshot::value)
        .containsExactly(2.0);
    assertThat(inA(() -> repository().findRecent(jobA, "m1", 14))).hasSize(2);
  }

  @Test
  @DisplayName("같은 시각이면 나중에 넣은 것(id DESC)이 먼저 온다")
  void findRecent_breaksTiesByIdDesc() {
    LocalDateTime t = ProactiveTime.nowUtc().minusMinutes(5);
    saveA(jobA, "m1", 1.0, t);
    saveA(jobA, "m1", 2.0, t);

    assertThat(inA(() -> repository().findRecent(jobA, "m1", 1)))
        .extracting(MetricSnapshotRepository.MetricSnapshot::value)
        .containsExactly(2.0, 1.0);
    assertThat(inA(() -> repository().findLatest(jobA, "m1")).value()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("findLatest 는 없으면 null 이다")
  void findLatest_returnsNullWhenEmpty() {
    assertThat(inA(() -> repository().findLatest(jobA, "nonexistent"))).isNull();
  }

  @Test
  @DisplayName("findLatest 는 가장 최근 스냅샷을 돌려준다")
  void findLatest_returnsMostRecent() {
    LocalDateTime now = ProactiveTime.nowUtc();
    saveA(jobA, "m1", 100.0, now.minusHours(1));
    saveA(jobA, "m1", 200.0, now);

    var latest = inA(() -> repository().findLatest(jobA, "m1"));

    assertThat(latest).isNotNull();
    assertThat(latest.value()).isEqualTo(200.0);
  }

  @Test
  @DisplayName("deleteOlderThan 은 기준 이전 행만 지우고 지운 행 수를 돌려준다")
  void deleteOlderThan_removesOldEntries() {
    LocalDateTime now = ProactiveTime.nowUtc();
    saveA(jobA, "m1", 100.0, now.minusDays(100));
    saveA(jobA, "m1", 150.0, now.minusDays(95));
    saveA(jobA, "m1", 200.0, now);

    int deleted = inA(() -> repository().deleteOlderThan(now.minusDays(90)));

    assertThat(deleted).isEqualTo(2);
    assertThat(inA(() -> repository().findRecent(jobA, "m1", 365))).hasSize(1);
    assertThat(inA(() -> repository().deleteOlderThan(now.minusDays(90)))).isZero();
  }

  @Test
  @DisplayName("다른 테넌트의 스냅샷은 조회·삭제 대상이 아니다(RLS)")
  void tenantIsolation() {
    LocalDateTime now = ProactiveTime.nowUtc();
    saveA(jobA, "m1", 1.0, now.minusDays(100));
    inB(
        () -> {
          repository().save(jobB, "m1", 2.0, now.minusDays(100));
          return null;
        });

    assertThat(inB(() -> repository().findRecent(jobA, "m1", 365))).isEmpty();
    assertThat(inB(() -> repository().findLatest(jobA, "m1"))).isNull();
    // B 범위의 삭제는 B 의 행 하나만 지운다 — A 의 행은 그대로다.
    assertThat(inB(() -> repository().deleteOlderThan(now.minusDays(90)))).isEqualTo(1);
    assertThat(inA(() -> repository().findRecent(jobA, "m1", 365))).hasSize(1);
  }
}

package com.smartfirehub.proactive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AnomalyEventRepository 계약 테스트 — 공개 메서드 2 개 전부(WD-485·WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis 는
 * Tibero 실서버(두 계정 RLS)다.
 *
 * <p>선행 데이터(사용자·선제 작업)는 {@code fx} 픽스처 seam 으로 만든다 — jOOQ DSLContext 를 직접 쓰지 않아 세 구현이 같은 시나리오를
 * 공유한다. 값은 DOUBLE PRECISION / BINARY_DOUBLE 왕복(소수·음수)을 확인한다.
 */
abstract class AnomalyEventRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract AnomalyEventRepository repository();

  private long jobA;
  private long jobB;

  @BeforeEach
  void setUpJobs() {
    long userA = fx.createUser(prefix + "-ua", null);
    long userB = fx.createUser(prefix + "-ub", null);
    jobA = fx.createProactiveJob(tenantA, userA, "Anomaly Test Job A");
    jobB = fx.createProactiveJob(tenantB, userB, "Anomaly Test Job B");
  }

  private AnomalyEvent event(long jobId, String metricId, String name, double cur, double dev) {
    return new AnomalyEvent(
        jobId, 1L, metricId, name, cur, 12.3, 5.2, dev, "medium", List.of(10.0, 12.0, 14.0));
  }

  @Test
  @DisplayName("저장한 이벤트를 jobId 로 조회하면 값이 그대로 돌아온다(소수·음수 BINARY_DOUBLE 왕복)")
  void save_and_findByJobId() {
    runA(() -> repository().save(event(jobA, "pipeline_failure_rate", "파이프라인 실패율", -45.5, 6.38)));

    var results = inA(() -> repository().findByJobId(jobA, 10));

    assertThat(results).hasSize(1);
    var r = results.get(0);
    assertThat(r.id()).isNotNull();
    assertThat(r.jobId()).isEqualTo(jobA);
    assertThat(r.metricId()).isEqualTo("pipeline_failure_rate");
    assertThat(r.metricName()).isEqualTo("파이프라인 실패율");
    assertThat(r.currentValue()).isEqualTo(-45.5);
    assertThat(r.mean()).isEqualTo(12.3);
    assertThat(r.stddev()).isEqualTo(5.2);
    assertThat(r.deviation()).isEqualTo(6.38);
    assertThat(r.sensitivity()).isEqualTo("medium");
    // detected_at 은 자바 UTC 값을 바인딩한다 — 방금 저장했으므로 UTC 기준 1분 안이어야 한다.
    assertThat(r.detectedAt())
        .isBetween(ProactiveTime.nowUtc().minusMinutes(1), ProactiveTime.nowUtc().plusMinutes(1));
  }

  @Test
  @DisplayName("limit 이 반환 건수를 제한한다")
  void findByJobId_respects_limit() {
    for (int i = 0; i < 3; i++) {
      int n = i;
      runA(() -> repository().save(event(jobA, "metric_" + n, "메트릭 " + n, n * 10.0, n * 1.5)));
    }

    assertThat(inA(() -> repository().findByJobId(jobA, 2))).hasSize(2);
    assertThat(inA(() -> repository().findByJobId(jobA, 10))).hasSize(3);
  }

  @Test
  @DisplayName("최근 순(detected_at DESC, 동시각이면 id DESC)으로 돌려준다")
  void findByJobId_ordersMostRecentFirst() {
    for (int i = 0; i < 3; i++) {
      int n = i;
      runA(() -> repository().save(event(jobA, "m" + n, "메트릭 " + n, n, n)));
    }

    var results = inA(() -> repository().findByJobId(jobA, 10));

    // 마지막에 저장한 것(m2)이 먼저 온다 — 같은 마이크로초여도 id DESC 로 결정된다.
    assertThat(results)
        .extracting(AnomalyEventRepository.AnomalyEventRecord::metricId)
        .containsExactly("m2", "m1", "m0");
  }

  @Test
  @DisplayName("없는 작업은 빈 목록이다")
  void findByJobId_unknownJobIsEmpty() {
    runA(() -> repository().save(event(jobA, "only_a", "A 전용", 1.0, 1.0)));

    assertThat(inA(() -> repository().findByJobId(jobA + 9_999_999L, 10))).isEmpty();
  }

  @Test
  @DisplayName("다른 테넌트는 남의 작업 이벤트를 못 본다(RLS)")
  void findByJobId_hidesOtherTenant() {
    runA(() -> repository().save(event(jobA, "only_a", "A 전용", 1.0, 1.0)));
    inB(
        () -> {
          repository().save(event(jobB, "only_b", "B 전용", 2.0, 2.0));
          return null;
        });

    assertThat(inB(() -> repository().findByJobId(jobA, 10))).isEmpty();
    assertThat(inB(() -> repository().findByJobId(jobB, 10)))
        .extracting(AnomalyEventRepository.AnomalyEventRecord::metricId)
        .containsExactly("only_b");
  }
}

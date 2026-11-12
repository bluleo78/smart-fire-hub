package com.smartfirehub.proactive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SystemMetricRepository 계약 테스트 — 공개 메서드 5 개 전부(WD-485·WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis 는
 * Tibero 실서버(두 계정 RLS)다.
 *
 * <p>이 저장소는 클래스 레벨 트랜잭션이 없다 — 호출자가 트랜잭션을 연 안에서 부른다(테스트는 {@code inA}·{@code inB} 가 그 경계를 연다). 테넌트마다
 * 새로 만든 행만 있으므로 개수는 정확한 값으로 단언한다.
 */
abstract class SystemMetricRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract SystemMetricRepository repository();

  private long userA;
  private long userB;
  private long pipelineA;

  @BeforeEach
  void setUpUsers() {
    userA = fx.createUser(prefix + "-ua", null);
    userB = fx.createUser(prefix + "-ub", null);
    pipelineA = fx.createPipeline(tenantA, prefix + "-pipe", true);
  }

  private long execution(String status, long executedBy, LocalDateTime createdAt) {
    long id = fx.createExecution(pipelineA, executedBy, status, null, null);
    fx.setExecutionField(id, "created_at", createdAt);
    return id;
  }

  @Test
  @DisplayName("findEnabledAnomalyJobs 는 활성 ANOMALY/BOTH 잡만, 설정 JSON 과 함께 돌려준다")
  void findEnabledAnomalyJobs_filtersEnabledAndTrigger() {
    long anomaly =
        fx.createProactiveJob(tenantA, userA, "a", true, "ANOMALY", "{\"metrics\":[\"m1\"]}");
    long both = fx.createProactiveJob(tenantA, userA, "b", true, "BOTH", null);
    fx.createProactiveJob(tenantA, userA, "c", true, "SCHEDULE", "{}");
    fx.createProactiveJob(tenantA, userA, "d", false, "ANOMALY", "{}");
    fx.createProactiveJob(tenantB, userB, "e", true, "ANOMALY", "{}");

    var jobs = inA(() -> repository().findEnabledAnomalyJobs());

    assertThat(jobs)
        .extracting(SystemMetricRepository.PolledJob::id)
        .containsExactlyInAnyOrder(anomaly, both);
    var anomalyJob = jobs.stream().filter(j -> j.id() == anomaly).findFirst().orElseThrow();
    assertThat(anomalyJob.userId()).isEqualTo(userA);
    // JSON 은 저장 형식(PG jsonb 는 공백 정규화)이 달라도 의미가 같아야 한다 — 키·값만 확인한다.
    assertThat(anomalyJob.config()).contains("metrics").contains("m1");
    var bothJob = jobs.stream().filter(j -> j.id() == both).findFirst().orElseThrow();
    assertThat(bothJob.config()).isEqualTo("{}");
  }

  @Test
  @DisplayName("파이프라인 실행 수는 since 이후만 세고 FAILED 는 따로 센다")
  void countPipelineExecutions() {
    LocalDateTime now = ProactiveTime.nowUtc();
    execution("COMPLETED", userA, now.minusHours(1));
    execution("FAILED", userA, now.minusHours(2));
    execution("FAILED", userA, now.minusDays(3));
    execution("COMPLETED", userA, now.minusDays(3));
    LocalDateTime since = now.minusDays(1);

    assertThat(inA(() -> repository().countPipelineExecutionsSince(since))).isEqualTo(2);
    assertThat(inA(() -> repository().countFailedPipelineExecutionsSince(since))).isEqualTo(1);
    assertThat(inA(() -> repository().countPipelineExecutionsSince(now.minusDays(30))))
        .isEqualTo(4);
    assertThat(inA(() -> repository().countPipelineExecutionsSince(now.plusDays(1)))).isZero();
  }

  @Test
  @DisplayName("실행자 수는 since 이후 서로 다른 사용자만 센다")
  void countDistinctPipelineExecutors() {
    LocalDateTime now = ProactiveTime.nowUtc();
    long other = fx.createUser(prefix + "-uc", null);
    execution("COMPLETED", userA, now.minusHours(1));
    execution("COMPLETED", userA, now.minusHours(2));
    execution("COMPLETED", other, now.minusHours(3));
    execution("COMPLETED", other, now.minusDays(5));

    assertThat(inA(() -> repository().countDistinctPipelineExecutorsSince(now.minusDays(1))))
        .isEqualTo(2);
    assertThat(inA(() -> repository().countDistinctPipelineExecutorsSince(now.minusDays(30))))
        .isEqualTo(2);
    assertThat(inA(() -> repository().countDistinctPipelineExecutorsSince(now.plusDays(1))))
        .isZero();
  }

  @Test
  @DisplayName("다른 테넌트의 실행·잡은 세지 않는다(RLS)")
  void tenantIsolation() {
    LocalDateTime now = ProactiveTime.nowUtc();
    execution("FAILED", userA, now.minusHours(1));
    fx.createProactiveJob(tenantA, userA, "a", true, "ANOMALY", "{}");

    assertThat(inB(() -> repository().countPipelineExecutionsSince(now.minusDays(1)))).isZero();
    assertThat(inB(() -> repository().countFailedPipelineExecutionsSince(now.minusDays(1))))
        .isZero();
    assertThat(inB(() -> repository().countDistinctPipelineExecutorsSince(now.minusDays(1))))
        .isZero();
    assertThat(inB(() -> repository().findEnabledAnomalyJobs())).isEmpty();
  }

  @Test
  @DisplayName("countDatasets 는 제한 없음이면 전부, 자격 가시성이면 보이는 등급만 센다")
  void countDatasets_respectsVisibility() {
    long secret = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long creator = fx.datasetCreator();
    // 기본 등급(rank 2) 데이터셋 둘 + 비밀 등급(rank 5) 하나.
    fx.createDataset(tenantA, creator, "t_" + prefix + "_1", null);
    fx.createDataset(tenantA, creator, "t_" + prefix + "_2", null);
    fx.createDataset(tenantA, creator, "t_" + prefix + "_s", secret);

    DatasetVisibility low =
        new DatasetVisibility(new Clearance(userA, tenantA, 2, Set.of(), false, Set.of()), null);
    DatasetVisibility high =
        new DatasetVisibility(new Clearance(userA, tenantA, 5, Set.of(), false, Set.of()), null);
    DatasetVisibility none =
        new DatasetVisibility(
            new Clearance(userA, tenantA, Clearance.NO_RANK, Set.of(), false, Set.of()), null);

    assertThat(inA(() -> repository().countDatasets(DatasetVisibility.UNRESTRICTED))).isEqualTo(3);
    assertThat(inA(() -> repository().countDatasets(low))).isEqualTo(2);
    assertThat(inA(() -> repository().countDatasets(high))).isEqualTo(3);
    assertThat(inA(() -> repository().countDatasets(none))).isZero();
    // 다른 테넌트에서는 이 데이터셋이 전혀 보이지 않는다.
    assertThat(inB(() -> repository().countDatasets(DatasetVisibility.UNRESTRICTED))).isZero();
  }
}

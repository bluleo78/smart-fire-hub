package com.smartfirehub.proactive.repository.jooq;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;
import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;

import com.smartfirehub.proactive.repository.SystemMetricRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * SystemMetricRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>이상탐지 폴러가 쓰는 조회 저장소 — 폴링 대상 잡 조회와 시스템 메트릭(파이프라인 실행·데이터셋·활성 사용자) 집계.
 *
 * <p>의도적으로 클래스 레벨 {@code @Transactional} 을 두지 않는다. 이 저장소가 읽는 {@code proactive_job}·{@code
 * dataset}·{@code pipeline_execution} 은 RLS 테이블이라 테넌트 GUC(app.tenant_id)가 트랜잭션-로컬로 주입돼야 하는데, 그 트랜잭션
 * 경계는 호출하는 {@code MetricPollerService} 의 {@code TransactionTemplate} 이 DB 구간에만 좁게 연다(외부 호출 동안 커넥션을
 * 점유하지 않기 위해). 따라서 호출자는 반드시 그 경계 안에서 부르며, 그러지 않으면 RLS 가 모든 행을 차단해 0 이 집계된다.
 */
@Repository
@RequiredArgsConstructor
public class JooqSystemMetricRepository implements SystemMetricRepository {

  private final DSLContext dsl;

  /** 활성화된 ANOMALY/BOTH 트리거 잡을 모두 읽는다. */
  @Override
  public List<PolledJob> findEnabledAnomalyJobs() {
    return dsl.select(
            PROACTIVE_JOB.ID,
            PROACTIVE_JOB.USER_ID,
            PROACTIVE_JOB.CONFIG,
            PROACTIVE_JOB.TRIGGER_TYPE)
        .from(PROACTIVE_JOB)
        .where(PROACTIVE_JOB.ENABLED.isTrue().and(PROACTIVE_JOB.TRIGGER_TYPE.in("ANOMALY", "BOTH")))
        .fetch()
        .map(
            job -> {
              var config = job.get(PROACTIVE_JOB.CONFIG);
              return new PolledJob(
                  job.get(PROACTIVE_JOB.ID),
                  job.get(PROACTIVE_JOB.USER_ID),
                  config != null ? config.data() : null);
            });
  }

  /** since 이후 생성된 파이프라인 실행 수. */
  @Override
  public int countPipelineExecutionsSince(LocalDateTime since) {
    return dsl.selectCount()
        .from(PIPELINE_EXECUTION)
        .where(PIPELINE_EXECUTION.CREATED_AT.ge(since))
        .fetchOne(0, int.class);
  }

  /** since 이후 생성된 파이프라인 실행 중 FAILED 상태의 수. */
  @Override
  public int countFailedPipelineExecutionsSince(LocalDateTime since) {
    return dsl.selectCount()
        .from(PIPELINE_EXECUTION)
        .where(PIPELINE_EXECUTION.CREATED_AT.ge(since).and(PIPELINE_EXECUTION.STATUS.eq("FAILED")))
        .fetchOne(0, int.class);
  }

  /** 가시성을 만족하는 데이터셋 수 — 가시성은 호출자가 DatasetAccessGuard 로 캡처해 넘기고, 여기서 "dataset" 이름 관례로 렌더한다. */
  @Override
  public int countDatasets(DatasetVisibility visibility) {
    Condition visible = JooqDatasetVisibility.condition(visibility);
    return dsl.selectCount().from(DATASET).where(visible).fetchOne(0, int.class);
  }

  /** since 이후 파이프라인을 실행한 서로 다른 사용자 수. */
  @Override
  public int countDistinctPipelineExecutorsSince(LocalDateTime since) {
    return dsl.selectCount()
        .from(
            dsl.selectDistinct(PIPELINE_EXECUTION.EXECUTED_BY)
                .from(PIPELINE_EXECUTION)
                .where(PIPELINE_EXECUTION.CREATED_AT.ge(since)))
        .fetchOne(0, int.class);
  }
}

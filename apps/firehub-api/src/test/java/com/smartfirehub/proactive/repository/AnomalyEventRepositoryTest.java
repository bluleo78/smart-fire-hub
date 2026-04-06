package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * AnomalyEventRepository 통합 테스트.
 *
 * <p>실제 테스트 DB(smartfirehub_test)를 사용하여 이상 탐지 이벤트의 저장 및 조회 기능을 검증한다. proactive_job은 user에 대한 FK를
 * 가지므로, 테스트 전에 user → proactive_job 순서로 데이터를 삽입한다. @Transactional 어노테이션으로 각 테스트 후 DB 상태를 자동으로 롤백한다.
 */
@Transactional
class AnomalyEventRepositoryTest extends IntegrationTestBase {

  @Autowired private AnomalyEventRepository repository;

  /** jOOQ DSLContext — 테스트용 선행 데이터(user, proactive_job) 삽입에 사용 */
  @Autowired private DSLContext dsl;

  /** 테스트에서 사용할 proactive_job ID */
  private Long testJobId;

  /**
   * 각 테스트 실행 전에 FK 제약 조건을 만족하는 선행 데이터를 삽입한다. anomaly_event.job_id → proactive_job.id → user.id 순으로
   * 의존성이 있으므로 user → proactive_job 순으로 먼저 생성한다.
   */
  @BeforeEach
  void setUp() {
    // user 레코드 먼저 삽입 (proactive_job FK 요건 충족)
    Long testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "anomaly_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Anomaly Test User")
            .set(USER.EMAIL, "anomaly_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // proactive_job 레코드 삽입 (anomaly_event FK 요건 충족)
    testJobId =
        dsl.insertInto(PROACTIVE_JOB)
            .set(PROACTIVE_JOB.USER_ID, testUserId)
            .set(PROACTIVE_JOB.NAME, "Anomaly Test Job")
            .set(PROACTIVE_JOB.PROMPT, "Test prompt")
            .set(PROACTIVE_JOB.CRON_EXPRESSION, "0 9 * * *")
            .set(PROACTIVE_JOB.TIMEZONE, "Asia/Seoul")
            .set(PROACTIVE_JOB.ENABLED, true)
            .returning(PROACTIVE_JOB.ID)
            .fetchOne()
            .getId();
  }

  /**
   * 이상 탐지 이벤트를 저장한 후 jobId로 조회 시 올바른 데이터가 반환되는지 검증한다. metricName과 deviation 필드 값이 저장된 그대로 조회되어야 한다.
   */
  @Test
  void save_and_findByJobId() {
    // Given: 이상 탐지 이벤트
    var event =
        new AnomalyEvent(
            testJobId,
            1L,
            "pipeline_failure_rate",
            "파이프라인 실패율",
            45.5,
            12.3,
            5.2,
            6.38,
            "medium",
            List.of(10.0, 12.0, 14.0));

    // When: 저장
    repository.save(event);

    // Then: 조회 시 저장된 이벤트 반환
    var results = repository.findByJobId(testJobId, 10);
    assertThat(results).isNotEmpty();
    assertThat(results.get(0).metricName()).isEqualTo("파이프라인 실패율");
    assertThat(results.get(0).deviation()).isEqualTo(6.38);
  }

  /**
   * findByJobId 호출 시 limit 파라미터가 실제로 반환 건수를 제한하는지 검증한다. 3개를 저장하고 limit=2로 조회하면 정확히 2개만 반환되어야 한다.
   */
  @Test
  void findByJobId_respects_limit() {
    // Given: 이벤트 3개 저장
    for (int i = 0; i < 3; i++) {
      repository.save(
          new AnomalyEvent(
              testJobId,
              1L,
              "metric_" + i,
              "메트릭 " + i,
              i * 10.0,
              5.0,
              2.0,
              i * 1.5,
              "high",
              List.of()));
    }

    // When: limit 2로 조회
    var results = repository.findByJobId(testJobId, 2);

    // Then: 최대 2개만 반환
    assertThat(results).hasSize(2);
  }
}

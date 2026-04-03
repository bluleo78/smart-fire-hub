package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class MetricSnapshotRepositoryTest extends IntegrationTestBase {

  @Autowired private MetricSnapshotRepository repository;
  @Autowired private DSLContext dsl;

  private Long testJobId;

  @BeforeEach
  void setUp() {
    Long testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "metric_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Metric Test User")
            .set(USER.EMAIL, "metric_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    testJobId =
        dsl.insertInto(PROACTIVE_JOB)
            .set(PROACTIVE_JOB.USER_ID, testUserId)
            .set(PROACTIVE_JOB.NAME, "Test Job")
            .set(PROACTIVE_JOB.PROMPT, "Test prompt")
            .set(PROACTIVE_JOB.CRON_EXPRESSION, "0 9 * * *")
            .set(PROACTIVE_JOB.TIMEZONE, "Asia/Seoul")
            .set(PROACTIVE_JOB.ENABLED, true)
            .returning(PROACTIVE_JOB.ID)
            .fetchOne()
            .getId();
  }

  @Test
  void save_and_findRecent_returnsInOrder() {
    repository.save(testJobId, "m1", 100.0, LocalDateTime.now().minusHours(2));
    repository.save(testJobId, "m1", 200.0, LocalDateTime.now().minusHours(1));
    repository.save(testJobId, "m1", 300.0, LocalDateTime.now());

    List<MetricSnapshotRepository.MetricSnapshot> snapshots =
        repository.findRecent(testJobId, "m1", 14);
    assertThat(snapshots).hasSize(3);
    assertThat(snapshots.get(0).value()).isEqualTo(300.0); // most recent first
  }

  @Test
  void findLatest_returnsNullWhenEmpty() {
    var latest = repository.findLatest(testJobId, "nonexistent");
    assertThat(latest).isNull();
  }

  @Test
  void findLatest_returnsMostRecent() {
    repository.save(testJobId, "m1", 100.0, LocalDateTime.now().minusHours(1));
    repository.save(testJobId, "m1", 200.0, LocalDateTime.now());
    var latest = repository.findLatest(testJobId, "m1");
    assertThat(latest).isNotNull();
    assertThat(latest.value()).isEqualTo(200.0);
  }

  @Test
  void deleteOlderThan_removesOldEntries() {
    repository.save(testJobId, "m1", 100.0, LocalDateTime.now().minusDays(100));
    repository.save(testJobId, "m1", 200.0, LocalDateTime.now());
    int deleted = repository.deleteOlderThan(LocalDateTime.now().minusDays(90));
    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findRecent(testJobId, "m1", 365)).hasSize(1);
  }
}

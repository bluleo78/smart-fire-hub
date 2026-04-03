# Phase 7-4: 이상 탐지 + 자동 알림 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스마트 작업에 이상 탐지 트리거를 추가한다. 시스템/데이터셋 메트릭을 주기적으로 수집하고, 이동평균+표준편차 기반 이상을 감지하면 스마트 작업을 자동 실행하여 AI 분석 리포트를 생성한다.

**Architecture:** `proactive_job.trigger_type` 컬럼으로 SCHEDULE/ANOMALY/BOTH 트리거를 구분. `metric_snapshot` 테이블에 메트릭 히스토리를 저장. `MetricPollerService`가 30초 간격으로 메트릭을 수집하고, `AnomalyDetector`가 이동평균+표준편차로 이상을 판단. 이상 감지 시 cooldown 체크 후 `ProactiveJobService.executeJob()`을 호출. 프론트엔드에 모니터링 탭을 추가하여 메트릭 설정 UI를 제공.

**Tech Stack:** Java 21 / Spring Boot 3.4 / jOOQ (Backend), React 19 / TypeScript / shadcn/ui (Frontend)

**설계 문서:** `docs/superpowers/specs/2026-04-03-phase-7-layer2-design.md` (섹션 4)

**실행 순서:**
```
Layer 1: Task 1 (DB 마이그레이션) → Task 2 (MetricSnapshotRepository) — 순차
Layer 2: Task 3 (AnomalyDetector) + Task 4 (MetricPollerService) — 순차 (3→4)
Layer 3: Task 5 (ProactiveJobService 확장) + Task 6 (ProactiveContextCollector 확장) — 병렬
Layer 4: Task 7 (프론트엔드 타입 + 검증 스키마) → Task 8 (모니터링 탭 UI) → Task 9 (페이지 통합)
Layer 5: Task 10 (통합 검증)
```

---

### Task 1: DB 마이그레이션 — trigger_type + metric_snapshot

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V44__add_anomaly_detection.sql`

- [ ] **Step 1: 마이그레이션 SQL 작성**

`apps/firehub-api/src/main/resources/db/migration/V44__add_anomaly_detection.sql` 생성:

```sql
-- proactive_job 테이블에 trigger_type 컬럼 추가
ALTER TABLE proactive_job ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(20) DEFAULT 'SCHEDULE';

-- 메트릭 스냅샷 히스토리 테이블
CREATE TABLE IF NOT EXISTS metric_snapshot (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES proactive_job(id) ON DELETE CASCADE,
    metric_id VARCHAR(100) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    collected_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_metric_snapshot_job_metric
    ON metric_snapshot(job_id, metric_id, collected_at DESC);
```

- [ ] **Step 2: baseline-version 업데이트**

`apps/firehub-api/src/main/resources/application.yml`에서 `baseline-version`을 `44`로 변경:

```yaml
spring:
  flyway:
    baseline-version: 44
```

- [ ] **Step 3: jOOQ 코드젠 실행 (DB가 실행 중일 때)**

Run: `cd apps/firehub-api && ./gradlew generateJooqSchemaSource`
Expected: PASS (metric_snapshot 테이블이 코드젠에 포함)

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V44__add_anomaly_detection.sql apps/firehub-api/src/main/resources/application.yml apps/firehub-api/src/main/generated/
git commit -m "feat(proactive): DB 마이그레이션 — trigger_type 컬럼 + metric_snapshot 테이블 (Phase 7-4)"
```

---

### Task 2: MetricSnapshotRepository

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/MetricSnapshotRepository.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/repository/MetricSnapshotRepositoryTest.java`

- [ ] **Step 1: 테스트 작성**

`apps/firehub-api/src/test/java/com/smartfirehub/proactive/repository/MetricSnapshotRepositoryTest.java` 생성:

```java
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

  private Long testUserId;
  private Long testJobId;

  @BeforeEach
  void setUp() {
    testUserId =
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
            .set(PROACTIVE_JOB.PROMPT, "test prompt")
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
    // most recent first
    assertThat(snapshots.get(0).value()).isEqualTo(300.0);
  }

  @Test
  void findLatest_returnsNullWhenEmpty() {
    var latest = repository.findLatest(testJobId, "m1");
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

    List<MetricSnapshotRepository.MetricSnapshot> remaining =
        repository.findRecent(testJobId, "m1", 14);
    assertThat(remaining).hasSize(1);
  }
}
```

- [ ] **Step 2: Repository 구현**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/MetricSnapshotRepository.java` 생성:

```java
package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MetricSnapshotRepository {

  private final DSLContext dsl;

  private static final Table<?> METRIC_SNAPSHOT = table(name("metric_snapshot"));
  private static final Field<Long> MS_ID = field(name("metric_snapshot", "id"), Long.class);
  private static final Field<Long> MS_JOB_ID =
      field(name("metric_snapshot", "job_id"), Long.class);
  private static final Field<String> MS_METRIC_ID =
      field(name("metric_snapshot", "metric_id"), String.class);
  private static final Field<Double> MS_VALUE =
      field(name("metric_snapshot", "value"), Double.class);
  private static final Field<LocalDateTime> MS_COLLECTED_AT =
      field(name("metric_snapshot", "collected_at"), LocalDateTime.class);

  public record MetricSnapshot(Long id, Long jobId, String metricId, double value, LocalDateTime collectedAt) {}

  public void save(Long jobId, String metricId, double value, LocalDateTime collectedAt) {
    dsl.insertInto(METRIC_SNAPSHOT)
        .set(MS_JOB_ID, jobId)
        .set(MS_METRIC_ID, metricId)
        .set(MS_VALUE, value)
        .set(MS_COLLECTED_AT, collectedAt)
        .execute();
  }

  /**
   * 최근 N일 이내의 스냅샷을 collected_at DESC 순으로 반환.
   */
  public List<MetricSnapshot> findRecent(Long jobId, String metricId, int days) {
    return dsl
        .select(MS_ID, MS_JOB_ID, MS_METRIC_ID, MS_VALUE, MS_COLLECTED_AT)
        .from(METRIC_SNAPSHOT)
        .where(MS_JOB_ID.eq(jobId))
        .and(MS_METRIC_ID.eq(metricId))
        .and(MS_COLLECTED_AT.ge(LocalDateTime.now().minusDays(days)))
        .orderBy(MS_COLLECTED_AT.desc())
        .fetch(r -> new MetricSnapshot(
            r.get(MS_ID),
            r.get(MS_JOB_ID),
            r.get(MS_METRIC_ID),
            r.get(MS_VALUE),
            r.get(MS_COLLECTED_AT)));
  }

  /**
   * 해당 메트릭의 가장 최근 스냅샷을 반환. 없으면 null.
   */
  public MetricSnapshot findLatest(Long jobId, String metricId) {
    return dsl
        .select(MS_ID, MS_JOB_ID, MS_METRIC_ID, MS_VALUE, MS_COLLECTED_AT)
        .from(METRIC_SNAPSHOT)
        .where(MS_JOB_ID.eq(jobId))
        .and(MS_METRIC_ID.eq(metricId))
        .orderBy(MS_COLLECTED_AT.desc())
        .limit(1)
        .fetchOne(r -> new MetricSnapshot(
            r.get(MS_ID),
            r.get(MS_JOB_ID),
            r.get(MS_METRIC_ID),
            r.get(MS_VALUE),
            r.get(MS_COLLECTED_AT)));
  }

  /**
   * 지정 시점 이전의 스냅샷 삭제 (히스토리 정리). 삭제된 행 수 반환.
   */
  public int deleteOlderThan(LocalDateTime cutoff) {
    return dsl.deleteFrom(METRIC_SNAPSHOT).where(MS_COLLECTED_AT.lt(cutoff)).execute();
  }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.proactive.repository.MetricSnapshotRepositoryTest"`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/MetricSnapshotRepository.java apps/firehub-api/src/test/java/com/smartfirehub/proactive/repository/MetricSnapshotRepositoryTest.java
git commit -m "feat(proactive): MetricSnapshotRepository — 메트릭 스냅샷 CRUD (Phase 7-4)"
```

---

### Task 3: AnomalyDetector — 이동평균 + 표준편차 기반 이상 판단

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/AnomalyDetector.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/AnomalyEvent.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/AnomalyDetectorTest.java`

- [ ] **Step 1: AnomalyEvent DTO 작성**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/AnomalyEvent.java` 생성:

```java
package com.smartfirehub.proactive.dto;

import java.util.List;

public record AnomalyEvent(
    Long jobId,
    Long userId,
    String metricId,
    String metricName,
    double currentValue,
    double mean,
    double stddev,
    double deviation,
    String sensitivity,
    List<Double> recentHistory) {}
```

- [ ] **Step 2: 테스트 작성**

`apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/AnomalyDetectorTest.java` 생성:

```java
package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnomalyDetectorTest {

  private AnomalyDetector detector;

  @BeforeEach
  void setUp() {
    detector = new AnomalyDetector();
  }

  private List<MetricSnapshot> generateHistory(double... values) {
    List<MetricSnapshot> snapshots = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      snapshots.add(new MetricSnapshot(
          (long) i, 1L, "m1", values[i],
          LocalDateTime.now().minusHours(values.length - i)));
    }
    return snapshots;
  }

  @Test
  void detect_withInsufficientHistory_returnsEmpty() {
    // 7개 미만이면 감지 보류
    List<MetricSnapshot> history = generateHistory(100, 200, 300);
    Optional<AnomalyEvent> result = detector.detect(1L, 1L, "m1", "테스트", 250.0, history, "medium");
    assertThat(result).isEmpty();
  }

  @Test
  void detect_normalValue_returnsEmpty() {
    // mean=100, stddev≈0 인 안정된 히스토리에서 100은 정상
    List<MetricSnapshot> history = generateHistory(100, 100, 100, 100, 100, 100, 100, 100, 100, 100);
    Optional<AnomalyEvent> result = detector.detect(1L, 1L, "m1", "테스트", 100.0, history, "medium");
    assertThat(result).isEmpty();
  }

  @Test
  void detect_mediumSensitivity_2sigma() {
    // mean=100, stddev=10 → 2σ 이탈 = |value - 100| > 20
    double[] vals = {90, 110, 95, 105, 100, 90, 110, 95, 105, 100};
    List<MetricSnapshot> history = generateHistory(vals);

    // 값 150은 (150-100)/~7.07 ≈ 7σ → 이상
    Optional<AnomalyEvent> result = detector.detect(1L, 1L, "m1", "테스트", 150.0, history, "medium");
    assertThat(result).isPresent();
    assertThat(result.get().deviation()).isGreaterThan(2.0);
  }

  @Test
  void detect_lowSensitivity_3sigma() {
    double[] vals = {90, 110, 95, 105, 100, 90, 110, 95, 105, 100};
    List<MetricSnapshot> history = generateHistory(vals);

    // 값 115는 (115-100)/~7.07 ≈ 2.1σ → low(3σ)에서는 정상
    Optional<AnomalyEvent> result = detector.detect(1L, 1L, "m1", "테스트", 115.0, history, "low");
    assertThat(result).isEmpty();
  }

  @Test
  void detect_highSensitivity_1_5sigma() {
    double[] vals = {90, 110, 95, 105, 100, 90, 110, 95, 105, 100};
    List<MetricSnapshot> history = generateHistory(vals);

    // 값 115는 (115-100)/~7.07 ≈ 2.1σ → high(1.5σ)에서는 이상
    Optional<AnomalyEvent> result = detector.detect(1L, 1L, "m1", "테스트", 115.0, history, "high");
    assertThat(result).isPresent();
  }

  @Test
  void detect_zeroStddev_ignores() {
    // 모든 값이 같으면 stddev=0, 약간의 차이도 이상으로 판단하지 않도록 보호
    List<MetricSnapshot> history = generateHistory(100, 100, 100, 100, 100, 100, 100);
    Optional<AnomalyEvent> result = detector.detect(1L, 1L, "m1", "테스트", 101.0, history, "medium");
    assertThat(result).isEmpty();
  }
}
```

- [ ] **Step 3: AnomalyDetector 구현**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/AnomalyDetector.java` 생성:

```java
package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AnomalyDetector {

  private static final int MIN_HISTORY_COUNT = 7;
  private static final double MIN_STDDEV = 1e-9;

  /**
   * 이동평균 + 표준편차 기반 이상 감지.
   *
   * @param jobId 스마트 작업 ID
   * @param userId 작업 소유자 ID
   * @param metricId 메트릭 ID
   * @param metricName 메트릭 표시명
   * @param currentValue 현재 수집된 값
   * @param history 최근 히스토리 (collected_at DESC 순)
   * @param sensitivity 감도: "low" (3σ), "medium" (2σ), "high" (1.5σ)
   * @return 이상이면 AnomalyEvent, 정상이면 empty
   */
  public Optional<AnomalyEvent> detect(
      Long jobId,
      Long userId,
      String metricId,
      String metricName,
      double currentValue,
      List<MetricSnapshot> history,
      String sensitivity) {

    if (history.size() < MIN_HISTORY_COUNT) {
      log.debug(
          "Anomaly detection skipped for job {} metric {}: insufficient history ({}/{})",
          jobId, metricId, history.size(), MIN_HISTORY_COUNT);
      return Optional.empty();
    }

    // 히스토리에서 평균, 표준편차 계산
    double sum = 0;
    for (MetricSnapshot s : history) {
      sum += s.value();
    }
    double mean = sum / history.size();

    double varianceSum = 0;
    for (MetricSnapshot s : history) {
      varianceSum += Math.pow(s.value() - mean, 2);
    }
    double stddev = Math.sqrt(varianceSum / history.size());

    // stddev가 0에 가까우면 감지 불가 (모든 값이 동일)
    if (stddev < MIN_STDDEV) {
      log.debug(
          "Anomaly detection skipped for job {} metric {}: stddev too small ({})",
          jobId, metricId, stddev);
      return Optional.empty();
    }

    // 편차 계산 (σ 단위)
    double deviation = Math.abs(currentValue - mean) / stddev;

    // sensitivity별 임계값
    double threshold = switch (sensitivity != null ? sensitivity : "medium") {
      case "low" -> 3.0;
      case "high" -> 1.5;
      default -> 2.0; // medium
    };

    if (deviation >= threshold) {
      log.info(
          "Anomaly detected for job {} metric {}: value={}, mean={}, stddev={}, deviation={:.2f}σ (threshold={:.1f}σ)",
          jobId, metricId, currentValue, mean, stddev, deviation, threshold);

      List<Double> recentValues = history.stream()
          .limit(20)
          .map(MetricSnapshot::value)
          .toList();

      return Optional.of(new AnomalyEvent(
          jobId, userId, metricId, metricName,
          currentValue, mean, stddev, deviation,
          sensitivity != null ? sensitivity : "medium",
          recentValues));
    }

    return Optional.empty();
  }
}
```

- [ ] **Step 4: 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.proactive.service.AnomalyDetectorTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/AnomalyDetector.java apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/AnomalyEvent.java apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/AnomalyDetectorTest.java
git commit -m "feat(proactive): AnomalyDetector — 이동평균+표준편차 기반 이상 감지 (Phase 7-4)"
```

---

### Task 4: MetricPollerService — @Scheduled 메트릭 수집

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricPollerService.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/MetricPollerServiceTest.java`

- [ ] **Step 1: 테스트 작성**

`apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/MetricPollerServiceTest.java` 생성:

```java
package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetricPollerServiceTest {

  @Mock private ProactiveJobRepository jobRepository;
  @Mock private MetricSnapshotRepository snapshotRepository;
  @Mock private AnomalyDetector anomalyDetector;
  @Mock private MetricCollector metricCollector;
  @Mock private ProactiveJobService jobService;

  private MetricPollerService pollerService;

  @BeforeEach
  void setUp() {
    pollerService = new MetricPollerService(
        jobRepository, snapshotRepository, anomalyDetector, metricCollector, jobService);
  }

  private ProactiveJobResponse buildJob(Long id, Long userId, Map<String, Object> config) {
    return new ProactiveJobResponse(
        id, userId, null, null, "Test Job", "prompt",
        "0 9 * * *", "Asia/Seoul", true, config,
        null, null, LocalDateTime.now(), LocalDateTime.now(), null);
  }

  @Test
  void pollMetrics_skipsJobsWithoutAnomalyConfig() {
    var job = buildJob(1L, 1L, Map.of());
    when(jobRepository.findAllEnabled()).thenReturn(List.of(job));

    pollerService.pollMetrics();

    verify(snapshotRepository, never()).save(anyLong(), anyString(), anyDouble(), any());
  }

  @Test
  void pollMetrics_collectsSystemMetric() {
    Map<String, Object> anomalyConfig = Map.of(
        "enabled", true,
        "sensitivity", "medium",
        "cooldownMinutes", 60,
        "metrics", List.of(Map.of(
            "id", "m1",
            "name", "파이프라인 실패율",
            "source", "system",
            "metricKey", "pipeline_failure_rate",
            "pollingInterval", 60)));
    var job = buildJob(1L, 1L, Map.of("anomaly", anomalyConfig));
    when(jobRepository.findAllEnabled()).thenReturn(List.of(job));
    when(snapshotRepository.findLatest(1L, "m1")).thenReturn(null); // no previous snapshot
    when(metricCollector.collectSystemMetric("pipeline_failure_rate")).thenReturn(Optional.of(5.0));
    when(snapshotRepository.findRecent(eq(1L), eq("m1"), anyInt())).thenReturn(List.of());

    pollerService.pollMetrics();

    verify(snapshotRepository).save(eq(1L), eq("m1"), eq(5.0), any(LocalDateTime.class));
  }

  @Test
  void pollMetrics_skipsMetricBeforePollingInterval() {
    Map<String, Object> anomalyConfig = Map.of(
        "enabled", true,
        "sensitivity", "medium",
        "cooldownMinutes", 60,
        "metrics", List.of(Map.of(
            "id", "m1",
            "name", "파이프라인 실패율",
            "source", "system",
            "metricKey", "pipeline_failure_rate",
            "pollingInterval", 300))); // 5분 간격
    var job = buildJob(1L, 1L, Map.of("anomaly", anomalyConfig));
    when(jobRepository.findAllEnabled()).thenReturn(List.of(job));

    // 마지막 수집이 1분 전
    var recentSnapshot = new MetricSnapshot(1L, 1L, "m1", 5.0, LocalDateTime.now().minusMinutes(1));
    when(snapshotRepository.findLatest(1L, "m1")).thenReturn(recentSnapshot);

    pollerService.pollMetrics();

    verify(snapshotRepository, never()).save(anyLong(), anyString(), anyDouble(), any());
  }

  @Test
  void pollMetrics_triggersJobOnAnomaly() {
    Map<String, Object> anomalyConfig = Map.of(
        "enabled", true,
        "sensitivity", "medium",
        "cooldownMinutes", 60,
        "metrics", List.of(Map.of(
            "id", "m1",
            "name", "파이프라인 실패율",
            "source", "system",
            "metricKey", "pipeline_failure_rate",
            "pollingInterval", 60)));
    var job = buildJob(1L, 1L, Map.of("anomaly", anomalyConfig));
    when(jobRepository.findAllEnabled()).thenReturn(List.of(job));
    when(snapshotRepository.findLatest(1L, "m1")).thenReturn(null);
    when(metricCollector.collectSystemMetric("pipeline_failure_rate")).thenReturn(Optional.of(50.0));

    var history = List.<MetricSnapshot>of(); // empty, but detector returns anomaly for test
    when(snapshotRepository.findRecent(eq(1L), eq("m1"), anyInt())).thenReturn(history);

    var anomaly = new AnomalyEvent(1L, 1L, "m1", "파이프라인 실패율", 50.0, 10.0, 5.0, 8.0, "medium", List.of());
    when(anomalyDetector.detect(eq(1L), eq(1L), eq("m1"), eq("파이프라인 실패율"), eq(50.0), any(), eq("medium")))
        .thenReturn(Optional.of(anomaly));

    pollerService.pollMetrics();

    verify(jobService).executeJobWithAnomaly(eq(1L), eq(1L), eq(anomaly));
  }
}
```

- [ ] **Step 2: MetricCollector 인터페이스 + 구현 작성**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricCollector.java` 생성:

```java
package com.smartfirehub.proactive.service;

import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.dataset.service.DataTableQueryService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetricCollector {

  private final DashboardService dashboardService;
  private final DataTableQueryService queryService;
  private final DSLContext dsl;

  /**
   * 시스템 메트릭 수집. 지원 키:
   * - pipeline_failure_rate: 최근 24시간 파이프라인 실패율 (%)
   * - pipeline_execution_count: 최근 24시간 실행 건수
   * - dataset_total_count: 전체 데이터셋 수
   * - active_user_count: 최근 24시간 활성 사용자 수
   */
  public Optional<Double> collectSystemMetric(String metricKey) {
    try {
      return switch (metricKey) {
        case "pipeline_failure_rate" -> {
          var health = dashboardService.getSystemHealth();
          yield Optional.of(100.0 - health.pipelineHealth().successRate());
        }
        case "pipeline_execution_count" -> {
          var health = dashboardService.getSystemHealth();
          yield Optional.of((double) health.pipelineHealth().totalExecutions());
        }
        case "dataset_total_count" -> {
          var stats = dashboardService.getStats();
          yield Optional.of((double) stats.totalDatasets());
        }
        case "active_user_count" -> {
          var stats = dashboardService.getStats();
          yield Optional.of((double) stats.totalUsers());
        }
        default -> {
          log.warn("Unknown system metric key: {}", metricKey);
          yield Optional.empty();
        }
      };
    } catch (Exception e) {
      log.error("Failed to collect system metric {}: {}", metricKey, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * 데이터셋 메트릭 수집. 사용자 SQL 쿼리를 실행하여 단일 숫자값 반환.
   */
  @Transactional(readOnly = true)
  public Optional<Double> collectDatasetMetric(String query) {
    try {
      var result = queryService.executeQuery(query, 1);
      if (result.rows().isEmpty() || result.rows().get(0).isEmpty()) {
        log.warn("Dataset metric query returned no results: {}", query);
        return Optional.empty();
      }
      // 첫 행의 첫 컬럼 값을 double로 변환
      Object firstValue = result.rows().get(0).values().iterator().next();
      if (firstValue == null) return Optional.empty();
      return Optional.of(Double.parseDouble(firstValue.toString()));
    } catch (Exception e) {
      log.error("Failed to collect dataset metric: {}", e.getMessage());
      return Optional.empty();
    }
  }
}
```

- [ ] **Step 3: MetricPollerService 구현**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricPollerService.java` 생성:

```java
package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository;
import com.smartfirehub.proactive.repository.MetricSnapshotRepository.MetricSnapshot;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricPollerService {

  private static final int HISTORY_DAYS = 14;

  private final ProactiveJobRepository jobRepository;
  private final MetricSnapshotRepository snapshotRepository;
  private final AnomalyDetector anomalyDetector;
  private final MetricCollector metricCollector;
  private final ProactiveJobService jobService;

  /**
   * 30초 간격으로 활성 스마트 작업의 메트릭을 수집하고 이상 감지를 실행.
   */
  @Scheduled(fixedDelay = 30_000)
  public void pollMetrics() {
    List<ProactiveJobResponse> jobs = jobRepository.findAllEnabled();

    for (ProactiveJobResponse job : jobs) {
      try {
        processJob(job);
      } catch (Exception e) {
        log.error("Failed to process metrics for job {}: {}", job.id(), e.getMessage());
      }
    }
  }

  /**
   * 90일 이전 스냅샷 정리 (매일 새벽 3시 실행).
   */
  @Scheduled(cron = "0 0 3 * * *")
  public void cleanupOldSnapshots() {
    int deleted = snapshotRepository.deleteOlderThan(LocalDateTime.now().minusDays(90));
    if (deleted > 0) {
      log.info("Cleaned up {} old metric snapshots", deleted);
    }
  }

  private void processJob(ProactiveJobResponse job) {
    Map<String, Object> config = job.config();
    if (config == null) return;

    @SuppressWarnings("unchecked")
    Map<String, Object> anomalyConfig = (Map<String, Object>) config.get("anomaly");
    if (anomalyConfig == null) return;

    Object enabledObj = anomalyConfig.get("enabled");
    if (!Boolean.TRUE.equals(enabledObj)) return;

    String sensitivity = (String) anomalyConfig.getOrDefault("sensitivity", "medium");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> metrics = (List<Map<String, Object>>) anomalyConfig.get("metrics");
    if (metrics == null || metrics.isEmpty()) return;

    for (Map<String, Object> metric : metrics) {
      try {
        processMetric(job, metric, sensitivity);
      } catch (Exception e) {
        log.error("Failed to process metric {} for job {}: {}",
            metric.get("id"), job.id(), e.getMessage());
      }
    }
  }

  private void processMetric(
      ProactiveJobResponse job, Map<String, Object> metric, String sensitivity) {

    String metricId = (String) metric.get("id");
    String metricName = (String) metric.get("name");
    String source = (String) metric.get("source");
    int pollingInterval = metric.get("pollingInterval") instanceof Number n ? n.intValue() : 300;

    // 폴링 간격 체크
    MetricSnapshot latest = snapshotRepository.findLatest(job.id(), metricId);
    if (latest != null) {
      LocalDateTime nextPollTime = latest.collectedAt().plusSeconds(pollingInterval);
      if (LocalDateTime.now().isBefore(nextPollTime)) {
        return; // 아직 폴링 시간이 안 됨
      }
    }

    // 메트릭 수집
    Optional<Double> valueOpt;
    if ("system".equals(source)) {
      String metricKey = (String) metric.get("metricKey");
      valueOpt = metricCollector.collectSystemMetric(metricKey);
    } else if ("dataset".equals(source)) {
      String query = (String) metric.get("query");
      valueOpt = metricCollector.collectDatasetMetric(query);
    } else {
      log.warn("Unknown metric source: {}", source);
      return;
    }

    if (valueOpt.isEmpty()) return;

    double value = valueOpt.get();
    LocalDateTime now = LocalDateTime.now();

    // 스냅샷 저장
    snapshotRepository.save(job.id(), metricId, value, now);

    // 히스토리 조회 + 이상 감지
    List<MetricSnapshot> history = snapshotRepository.findRecent(job.id(), metricId, HISTORY_DAYS);
    Optional<AnomalyEvent> anomaly = anomalyDetector.detect(
        job.id(), job.userId(), metricId, metricName, value, history, sensitivity);

    if (anomaly.isPresent()) {
      log.info("Anomaly detected for job {} metric {}: triggering execution", job.id(), metricId);
      try {
        jobService.executeJobWithAnomaly(job.id(), job.userId(), anomaly.get());
      } catch (Exception e) {
        log.error("Failed to trigger job {} after anomaly: {}", job.id(), e.getMessage());
      }
    }
  }
}
```

- [ ] **Step 4: 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.proactive.service.MetricPollerServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricPollerService.java apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricCollector.java apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/MetricPollerServiceTest.java
git commit -m "feat(proactive): MetricPollerService + MetricCollector — 주기적 메트릭 수집 + 이상 감지 트리거 (Phase 7-4)"
```

---

### Task 5: ProactiveJobService 확장 — AnomalyEvent 처리 + cooldown

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/ProactiveJobRepository.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveJobServiceTest.java`

- [ ] **Step 1: ProactiveJobRepository에 trigger_type 필드 추가**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/ProactiveJobRepository.java`에 필드 상수 추가:

```java
private static final Field<String> PJ_TRIGGER_TYPE =
    field(name("proactive_job", "trigger_type"), String.class);
```

`ProactiveJobResponse` record에 `triggerType` 추가가 필요하므로 DTO도 수정.

- [ ] **Step 2: ProactiveJobResponse에 triggerType 추가**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/ProactiveJobResponse.java` 수정:

```java
public record ProactiveJobResponse(
    Long id,
    Long userId,
    Long templateId,
    String templateName,
    String name,
    String prompt,
    String cronExpression,
    String timezone,
    Boolean enabled,
    String triggerType,
    Map<String, Object> config,
    LocalDateTime lastExecutedAt,
    LocalDateTime nextExecuteAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    ProactiveJobExecutionResponse lastExecution) {}
```

- [ ] **Step 3: ProactiveJobRepository의 toResponse, select 목록, create, update에 triggerType 반영**

모든 `select()` 호출에 `PJ_TRIGGER_TYPE` 필드 추가. `toResponse`에서 `r.get(PJ_TRIGGER_TYPE)` 포함. `create()`에 `triggerType` 파라미터 추가. `update()`에 `triggerType` 파라미터 추가.

`toResponse` 수정:
```java
private ProactiveJobResponse toResponse(
    org.jooq.Record r,
    ProactiveJobExecutionResponse lastExecution) {
  try {
    JSONB configJsonb = r.get(PJ_CONFIG);
    Map<String, Object> config =
        configJsonb != null
            ? objectMapper.readValue(configJsonb.data(), new TypeReference<>() {})
            : Map.of();
    return new ProactiveJobResponse(
        r.get(PJ_ID),
        r.get(PJ_USER_ID),
        r.get(PJ_TEMPLATE_ID),
        r.get(RT_NAME),
        r.get(PJ_NAME),
        r.get(PJ_PROMPT),
        r.get(PJ_CRON_EXPRESSION),
        r.get(PJ_TIMEZONE),
        r.get(PJ_ENABLED),
        r.get(PJ_TRIGGER_TYPE),
        config,
        r.get(PJ_LAST_EXECUTED_AT),
        r.get(PJ_NEXT_EXECUTE_AT),
        r.get(PJ_CREATED_AT),
        r.get(PJ_UPDATED_AT),
        lastExecution);
  } catch (Exception e) {
    throw new RuntimeException("Failed to deserialize config", e);
  }
}
```

- [ ] **Step 4: ProactiveJobService에 executeJobWithAnomaly 메서드 추가**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java`에 추가:

```java
// cooldown 추적: jobId -> 마지막 이상 감지 실행 시각
private final ConcurrentHashMap<Long, LocalDateTime> lastAnomalyExecution = new ConcurrentHashMap<>();

// 현재 이상 이벤트 (executeJob에서 컨텍스트에 추가하기 위해 ThreadLocal 사용)
private final ThreadLocal<AnomalyEvent> currentAnomalyEvent = new ThreadLocal<>();

public AnomalyEvent getCurrentAnomalyEvent() {
  return currentAnomalyEvent.get();
}

/**
 * 이상 감지 이벤트로 인한 작업 실행. cooldown 체크 포함.
 */
public void executeJobWithAnomaly(Long jobId, Long userId, AnomalyEvent anomalyEvent) {
  // cooldown 체크
  ProactiveJobResponse job = proactiveJobRepository.findById(jobId, userId).orElse(null);
  if (job == null) return;

  @SuppressWarnings("unchecked")
  Map<String, Object> anomalyConfig =
      job.config() != null ? (Map<String, Object>) job.config().get("anomaly") : null;
  int cooldownMinutes = 60;
  if (anomalyConfig != null && anomalyConfig.get("cooldownMinutes") instanceof Number n) {
    cooldownMinutes = n.intValue();
  }

  LocalDateTime lastExec = lastAnomalyExecution.get(jobId);
  if (lastExec != null && lastExec.plusMinutes(cooldownMinutes).isAfter(LocalDateTime.now())) {
    log.info("Anomaly execution for job {} skipped: cooldown active (last={})", jobId, lastExec);
    return;
  }

  lastAnomalyExecution.put(jobId, LocalDateTime.now());
  currentAnomalyEvent.set(anomalyEvent);
  try {
    executeJob(jobId, userId);
  } finally {
    currentAnomalyEvent.remove();
  }
}
```

- [ ] **Step 5: CreateProactiveJobRequest, UpdateProactiveJobRequest에 triggerType 추가**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/CreateProactiveJobRequest.java`:
```java
public record CreateProactiveJobRequest(
    String name,
    String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    String triggerType,
    Map<String, Object> config) {}
```

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/UpdateProactiveJobRequest.java`:
```java
// triggerType 필드 추가 (기존 필드 유지)
```

- [ ] **Step 6: ProactiveJobServiceTest에 executeJobWithAnomaly 테스트 추가**

기존 `ProactiveJobServiceTest.java`에 테스트 추가:

```java
@Test
void executeJobWithAnomaly_respectsCooldown() {
  // 작업 생성
  var request = buildCreateRequest("anomaly_test");
  var created = proactiveJobService.createJob(request, testUserId);

  // 첫 실행 mock
  when(proactiveContextCollector.collectContext(any(), anyLong())).thenReturn("{}");
  when(proactiveAiClient.execute(anyLong(), anyString(), anyString(), anyString(), any()))
      .thenReturn(new ProactiveResult("title", List.of()));

  var anomaly = new AnomalyEvent(
      created.id(), testUserId, "m1", "테스트", 100.0, 50.0, 10.0, 5.0, "medium", List.of());

  // 첫 실행: 성공
  rawJobService.executeJobWithAnomaly(created.id(), testUserId, anomaly);
  verify(proactiveAiClient, times(1)).execute(anyLong(), anyString(), anyString(), anyString(), any());

  // 바로 두 번째 실행: cooldown으로 스킵
  rawJobService.executeJobWithAnomaly(created.id(), testUserId, anomaly);
  // 여전히 1회만 호출됨 (cooldown 중)
  verify(proactiveAiClient, times(1)).execute(anyLong(), anyString(), anyString(), anyString(), any());
}
```

- [ ] **Step 7: 컴파일 + 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.proactive.service.ProactiveJobServiceTest"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/ProactiveJobRepository.java apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/ apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveJobServiceTest.java
git commit -m "feat(proactive): ProactiveJobService — AnomalyEvent 처리 + cooldown (Phase 7-4)"
```

---

### Task 6: ProactiveContextCollector 확장 — anomaly 컨텍스트 추가

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveContextCollector.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveContextCollectorTest.java`

- [ ] **Step 1: ProactiveContextCollector에 anomaly 컨텍스트 추가**

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveContextCollector.java`에서:

1. `ProactiveJobService` 의존성 추가 (순환 방지를 위해 `@Lazy` 사용):

```java
private final ProactiveJobService proactiveJobService;

public ProactiveContextCollector(
    DashboardService dashboardService,
    ObjectMapper objectMapper,
    ProactiveJobExecutionRepository executionRepository,
    @Lazy ProactiveJobService proactiveJobService) {
  this.dashboardService = dashboardService;
  this.objectMapper = objectMapper;
  this.executionRepository = executionRepository;
  this.proactiveJobService = proactiveJobService;
}
```

2. `collectContext()` 메서드에서 이전 실행 컨텍스트 수집 후, anomaly 컨텍스트를 추가:

```java
// 6. Anomaly context (이상 감지로 트리거된 경우)
AnomalyEvent anomalyEvent = proactiveJobService.getCurrentAnomalyEvent();
if (anomalyEvent != null) {
  Map<String, Object> anomalyContext = new HashMap<>();
  anomalyContext.put("metricName", anomalyEvent.metricName());
  anomalyContext.put("currentValue", anomalyEvent.currentValue());
  anomalyContext.put("expectedRange", Map.of(
      "mean", anomalyEvent.mean(),
      "stddev", anomalyEvent.stddev()));
  anomalyContext.put("deviation", anomalyEvent.deviation());
  anomalyContext.put("sensitivity", anomalyEvent.sensitivity());
  anomalyContext.put("recentHistory", anomalyEvent.recentHistory());
  context.put("anomaly", anomalyContext);
}
```

- [ ] **Step 2: ProactiveContextCollectorTest에 anomaly 컨텍스트 테스트 추가**

기존 테스트 파일에 추가:

```java
@Test
void collectContext_includesAnomalyContext_whenAnomalyEventPresent() throws Exception {
  // DashboardService mock
  when(dashboardService.getStats()).thenReturn(/* mock stats */);
  when(dashboardService.getSystemHealth()).thenReturn(/* mock health */);
  when(dashboardService.getAttentionItems()).thenReturn(List.of());
  when(dashboardService.getActivityFeed(any(), any(), anyInt(), anyInt())).thenReturn(/* mock */);

  // ProactiveJobService에 anomaly event 설정
  var anomaly = new AnomalyEvent(
      1L, 1L, "m1", "파이프라인 실패율", 50.0, 10.0, 5.0, 8.0, "medium", List.of(10.0, 12.0, 50.0));
  when(proactiveJobService.getCurrentAnomalyEvent()).thenReturn(anomaly);

  String context = contextCollector.collectContext(Map.of(), 1L);

  assertThat(context).contains("anomaly");
  assertThat(context).contains("파이프라인 실패율");
  assertThat(context).contains("50.0");
}
```

- [ ] **Step 3: 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.proactive.service.ProactiveContextCollectorTest"`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveContextCollector.java apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveContextCollectorTest.java
git commit -m "feat(proactive): ProactiveContextCollector — anomaly 컨텍스트 추가 (Phase 7-4)"
```

---

### Task 7: 프론트엔드 타입 정의 + 검증 스키마 확장

**Files:**
- Modify: `apps/firehub-web/src/api/proactive.ts`
- Modify: `apps/firehub-web/src/lib/validations/proactive-job.ts`

- [ ] **Step 1: ProactiveJob 인터페이스에 triggerType 추가**

`apps/firehub-web/src/api/proactive.ts`의 `ProactiveJob` 인터페이스에 추가:

```typescript
export interface ProactiveJob {
  id: number;
  userId: number;
  templateId: number | null;
  templateName: string | null;
  name: string;
  prompt: string;
  cronExpression: string;
  timezone: string;
  enabled: boolean;
  triggerType: 'SCHEDULE' | 'ANOMALY' | 'BOTH';
  config: Record<string, unknown>;
  lastExecutedAt: string | null;
  nextExecuteAt: string | null;
  createdAt: string;
  updatedAt: string;
  lastExecution: ProactiveJobExecution | null;
}
```

- [ ] **Step 2: AnomalyConfig 타입 정의 추가**

같은 파일에 anomaly config 관련 타입 추가:

```typescript
export type AnomalySensitivity = 'low' | 'medium' | 'high';

export interface AnomalyMetricConfig {
  id: string;
  name: string;
  source: 'system' | 'dataset';
  metricKey?: string;       // system 메트릭용
  datasetId?: number;       // dataset 메트릭용
  query?: string;           // dataset 메트릭용
  pollingInterval: number;  // 초 단위
}

export interface AnomalyConfig {
  enabled: boolean;
  metrics: AnomalyMetricConfig[];
  sensitivity: AnomalySensitivity;
  cooldownMinutes: number;
}

export const SYSTEM_METRICS = [
  { key: 'pipeline_failure_rate', label: '파이프라인 실패율', unit: '%' },
  { key: 'pipeline_execution_count', label: '파이프라인 실행 건수', unit: '건' },
  { key: 'dataset_total_count', label: '전체 데이터셋 수', unit: '개' },
  { key: 'active_user_count', label: '활성 사용자 수', unit: '명' },
] as const;
```

- [ ] **Step 3: Zod 검증 스키마에 anomaly 설정 추가**

`apps/firehub-web/src/lib/validations/proactive-job.ts` 수정:

```typescript
import { z } from 'zod';

export const anomalyMetricSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1, '메트릭 이름을 입력하세요'),
  source: z.enum(['system', 'dataset']),
  metricKey: z.string().optional(),
  datasetId: z.number().optional(),
  query: z.string().optional(),
  pollingInterval: z.number().min(30, '최소 30초').max(86400, '최대 24시간'),
});

export const anomalyConfigSchema = z.object({
  enabled: z.boolean(),
  metrics: z.array(anomalyMetricSchema),
  sensitivity: z.enum(['low', 'medium', 'high']),
  cooldownMinutes: z.number().min(1, '최소 1분').max(1440, '최대 24시간'),
});

export const channelConfigSchema = z.object({
  type: z.enum(['CHAT', 'EMAIL']),
  recipientUserIds: z.array(z.number()),
  recipientEmails: z.array(z.string().email('올바른 이메일 형식이 아닙니다')),
  attachPdf: z.boolean().optional(),
});

export const proactiveJobSchema = z.object({
  name: z.string().min(1, '작업 이름을 입력하세요'),
  prompt: z.string().min(1, '분석 프롬프트를 입력하세요'),
  templateId: z.number().nullable().optional(),
  cronExpression: z.string().min(1, '실행 주기를 설정하세요'),
  timezone: z.string().min(1),
  triggerType: z.enum(['SCHEDULE', 'ANOMALY', 'BOTH']).optional(),
  config: z.object({
    channels: z.array(channelConfigSchema),
    anomaly: anomalyConfigSchema.optional(),
  }),
});

export type ProactiveJobFormValues = z.infer<typeof proactiveJobSchema>;
export type ChannelConfigValues = z.infer<typeof channelConfigSchema>;
export type AnomalyConfigValues = z.infer<typeof anomalyConfigSchema>;
export type AnomalyMetricValues = z.infer<typeof anomalyMetricSchema>;
```

- [ ] **Step 4: CreateProactiveJobRequest, UpdateProactiveJobRequest에 triggerType 추가**

`apps/firehub-web/src/api/proactive.ts`의 `CreateProactiveJobRequest`에 `triggerType` 추가. API 호출 부분도 확인.

- [ ] **Step 5: 프론트엔드 타입체크 실행**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src/api/proactive.ts apps/firehub-web/src/lib/validations/proactive-job.ts
git commit -m "feat(proactive): 프론트엔드 타입 + 검증 스키마 — triggerType, AnomalyConfig (Phase 7-4)"
```

---

### Task 8: 모니터링 탭 UI 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx`

- [ ] **Step 1: JobMonitoringTab 컴포넌트 작성**

`apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx` 생성:

```typescript
import { Plus, Trash2 } from 'lucide-react';
import { nanoid } from 'nanoid';
import { type UseFormReturn } from 'react-hook-form';

import { type AnomalyMetricConfig, SYSTEM_METRICS } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { useDatasets } from '@/hooks/queries/useDatasets';
import type { ProactiveJobFormValues } from '@/lib/validations/proactive-job';

interface JobMonitoringTabProps {
  isEditing: boolean;
  form: UseFormReturn<ProactiveJobFormValues>;
}

const SENSITIVITY_OPTIONS = [
  { value: 'low', label: '낮음 (3σ)', description: '큰 이상만 감지' },
  { value: 'medium', label: '보통 (2σ)', description: '일반적인 이상 감지' },
  { value: 'high', label: '높음 (1.5σ)', description: '작은 변화도 감지' },
] as const;

const POLLING_PRESETS = [
  { label: '30초', value: 30 },
  { label: '1분', value: 60 },
  { label: '5분', value: 300 },
  { label: '15분', value: 900 },
  { label: '1시간', value: 3600 },
] as const;

export default function JobMonitoringTab({ isEditing, form }: JobMonitoringTabProps) {
  const { watch, setValue } = form;
  const anomaly = watch('config.anomaly');
  const enabled = anomaly?.enabled ?? false;
  const metrics = (anomaly?.metrics ?? []) as AnomalyMetricConfig[];
  const sensitivity = anomaly?.sensitivity ?? 'medium';
  const cooldownMinutes = anomaly?.cooldownMinutes ?? 60;

  const { data: datasetsData } = useDatasets({ page: 0, size: 100 });
  const datasets = datasetsData?.content ?? [];

  const updateAnomaly = (patch: Partial<typeof anomaly>) => {
    setValue('config.anomaly', {
      enabled,
      metrics,
      sensitivity,
      cooldownMinutes,
      ...anomaly,
      ...patch,
    } as any);
  };

  const addSystemMetric = (metricKey: string) => {
    const meta = SYSTEM_METRICS.find((m) => m.key === metricKey);
    if (!meta) return;
    const newMetric: AnomalyMetricConfig = {
      id: nanoid(8),
      name: meta.label,
      source: 'system',
      metricKey: meta.key,
      pollingInterval: 300,
    };
    updateAnomaly({ metrics: [...metrics, newMetric] });
  };

  const addDatasetMetric = () => {
    const newMetric: AnomalyMetricConfig = {
      id: nanoid(8),
      name: '',
      source: 'dataset',
      datasetId: undefined,
      query: '',
      pollingInterval: 300,
    };
    updateAnomaly({ metrics: [...metrics, newMetric] });
  };

  const removeMetric = (id: string) => {
    updateAnomaly({ metrics: metrics.filter((m) => m.id !== id) });
  };

  const updateMetric = (id: string, patch: Partial<AnomalyMetricConfig>) => {
    updateAnomaly({
      metrics: metrics.map((m) => (m.id === id ? { ...m, ...patch } : m)),
    });
  };

  // Read-only view
  if (!isEditing) {
    return (
      <div className="space-y-6 pt-4">
        <div className="rounded-lg border p-4 space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold">이상 감지</h3>
            <Badge variant={enabled ? 'default' : 'secondary'}>
              {enabled ? '활성' : '비활성'}
            </Badge>
          </div>

          {enabled && (
            <>
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">감도</p>
                  <p className="text-sm">
                    {SENSITIVITY_OPTIONS.find((s) => s.value === sensitivity)?.label ?? sensitivity}
                  </p>
                </div>
                <div className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">재알림 방지</p>
                  <p className="text-sm">{cooldownMinutes}분</p>
                </div>
              </div>

              <Separator />

              <div className="space-y-2">
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
                  모니터링 메트릭 ({metrics.length}개)
                </p>
                {metrics.length === 0 ? (
                  <p className="text-sm text-muted-foreground">설정된 메트릭이 없습니다.</p>
                ) : (
                  <div className="space-y-2">
                    {metrics.map((m) => (
                      <div key={m.id} className="flex items-center gap-2 rounded border p-2">
                        <Badge variant="outline">{m.source === 'system' ? '시스템' : '데이터셋'}</Badge>
                        <span className="text-sm font-medium">{m.name || '(이름 없음)'}</span>
                        <span className="text-xs text-muted-foreground ml-auto">
                          {POLLING_PRESETS.find((p) => p.value === m.pollingInterval)?.label ?? `${m.pollingInterval}초`}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    );
  }

  // Edit mode
  return (
    <div className="space-y-6 pt-4 max-w-2xl">
      {/* 이상 감지 활성화 */}
      <div className="flex items-center justify-between rounded-lg border p-4">
        <div className="space-y-0.5">
          <Label>이상 감지 활성화</Label>
          <p className="text-xs text-muted-foreground">
            메트릭을 모니터링하여 이상 발생 시 자동으로 리포트를 생성합니다.
          </p>
        </div>
        <Switch
          checked={enabled}
          onCheckedChange={(checked) => {
            if (!anomaly) {
              setValue('config.anomaly', {
                enabled: checked,
                metrics: [],
                sensitivity: 'medium',
                cooldownMinutes: 60,
              } as any);
            } else {
              updateAnomaly({ enabled: checked });
            }
          }}
        />
      </div>

      {enabled && (
        <>
          {/* 감도 + 재알림 방지 */}
          <div className="rounded-lg border p-4 space-y-4">
            <h3 className="text-sm font-semibold">감지 설정</h3>

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>감도</Label>
                <Select
                  value={sensitivity}
                  onValueChange={(v) => updateAnomaly({ sensitivity: v as any })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {SENSITIVITY_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        <div>
                          <span>{opt.label}</span>
                          <span className="text-xs text-muted-foreground ml-2">{opt.description}</span>
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label>재알림 방지 (분)</Label>
                <Input
                  type="number"
                  min={1}
                  max={1440}
                  value={cooldownMinutes}
                  onChange={(e) => updateAnomaly({ cooldownMinutes: parseInt(e.target.value) || 60 })}
                />
                <p className="text-xs text-muted-foreground">이상 감지 후 이 시간 동안 재알림 안 함</p>
              </div>
            </div>
          </div>

          {/* 모니터링 메트릭 */}
          <div className="rounded-lg border p-4 space-y-4">
            <h3 className="text-sm font-semibold">모니터링 메트릭</h3>

            {metrics.length > 0 && (
              <div className="space-y-3">
                {metrics.map((m) => (
                  <div key={m.id} className="rounded border p-3 space-y-3">
                    <div className="flex items-center justify-between">
                      <Badge variant="outline">{m.source === 'system' ? '시스템' : '데이터셋'}</Badge>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6"
                        onClick={() => removeMetric(m.id)}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>

                    <div className="space-y-2">
                      <Label>메트릭 이름</Label>
                      <Input
                        value={m.name}
                        onChange={(e) => updateMetric(m.id, { name: e.target.value })}
                        placeholder="메트릭 표시 이름"
                      />
                    </div>

                    {m.source === 'system' && (
                      <div className="space-y-2">
                        <Label>시스템 메트릭</Label>
                        <Select
                          value={m.metricKey ?? ''}
                          onValueChange={(v) => {
                            const meta = SYSTEM_METRICS.find((s) => s.key === v);
                            updateMetric(m.id, { metricKey: v, name: meta?.label ?? m.name });
                          }}
                        >
                          <SelectTrigger>
                            <SelectValue placeholder="메트릭 선택" />
                          </SelectTrigger>
                          <SelectContent>
                            {SYSTEM_METRICS.map((sm) => (
                              <SelectItem key={sm.key} value={sm.key}>
                                {sm.label} ({sm.unit})
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                    )}

                    {m.source === 'dataset' && (
                      <>
                        <div className="space-y-2">
                          <Label>데이터셋</Label>
                          <Select
                            value={m.datasetId ? String(m.datasetId) : ''}
                            onValueChange={(v) => updateMetric(m.id, { datasetId: Number(v) })}
                          >
                            <SelectTrigger>
                              <SelectValue placeholder="데이터셋 선택" />
                            </SelectTrigger>
                            <SelectContent>
                              {datasets.map((ds: any) => (
                                <SelectItem key={ds.id} value={String(ds.id)}>
                                  {ds.name}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                        <div className="space-y-2">
                          <Label>SQL 쿼리</Label>
                          <Textarea
                            value={m.query ?? ''}
                            onChange={(e) => updateMetric(m.id, { query: e.target.value })}
                            placeholder="SELECT SUM(amount) as value FROM sales WHERE date = CURRENT_DATE"
                            rows={3}
                            className="font-mono text-xs"
                          />
                          <p className="text-xs text-muted-foreground">
                            단일 숫자값을 반환하는 SELECT 쿼리를 입력하세요.
                          </p>
                        </div>
                      </>
                    )}

                    <div className="space-y-2">
                      <Label>수집 간격</Label>
                      <Select
                        value={String(m.pollingInterval)}
                        onValueChange={(v) => updateMetric(m.id, { pollingInterval: Number(v) })}
                      >
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {POLLING_PRESETS.map((p) => (
                            <SelectItem key={p.value} value={String(p.value)}>
                              {p.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>
                ))}
              </div>
            )}

            <div className="flex gap-2">
              <Select onValueChange={(v) => addSystemMetric(v)}>
                <SelectTrigger className="w-auto">
                  <Plus className="h-3.5 w-3.5 mr-1" />
                  시스템 메트릭 추가
                </SelectTrigger>
                <SelectContent>
                  {SYSTEM_METRICS.filter(
                    (sm) => !metrics.some((m) => m.source === 'system' && m.metricKey === sm.key),
                  ).map((sm) => (
                    <SelectItem key={sm.key} value={sm.key}>
                      {sm.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <Button variant="outline" size="sm" onClick={addDatasetMetric}>
                <Plus className="h-3.5 w-3.5 mr-1" />
                데이터셋 메트릭 추가
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 2: nanoid 의존성 확인/추가**

`nanoid`가 이미 설치되어 있는지 확인. 없으면:
Run: `cd apps/firehub-web && pnpm add nanoid`

없을 경우 `crypto.randomUUID()` 또는 `Math.random().toString(36).slice(2, 10)`으로 대체 가능.

- [ ] **Step 3: 프론트엔드 타입체크 실행**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx
git commit -m "feat(proactive): JobMonitoringTab — 이상 감지 모니터링 설정 UI (Phase 7-4)"
```

---

### Task 9: ProactiveJobDetailPage에 모니터링 탭 통합

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx`
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx`

- [ ] **Step 1: ProactiveJobDetailPage에 모니터링 탭 추가**

`apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx`에서:

1. import 추가:
```typescript
import JobMonitoringTab from './tabs/JobMonitoringTab';
```

2. `buildDefaultValues` 함수에서 `config`에 `anomaly` 포함:
```typescript
return {
  name: job.name,
  prompt: job.prompt,
  templateId: job.templateId ?? null,
  cronExpression: job.cronExpression,
  timezone: job.timezone ?? 'Asia/Seoul',
  triggerType: job.triggerType ?? 'SCHEDULE',
  config: { channels, anomaly: job.config?.anomaly as any },
};
```

3. `handleSave`에서 `triggerType` 포함:
```typescript
const payload = {
  name: values.name,
  prompt: values.prompt,
  templateId: values.templateId ?? null,
  cronExpression: values.cronExpression,
  timezone: values.timezone,
  triggerType: values.triggerType ?? 'SCHEDULE',
  config: values.config,
};
```

4. 탭 목록에 모니터링 탭 추가 (실행 이력 탭 앞):
```typescript
<TabsList>
  <TabsTrigger value="overview">개요</TabsTrigger>
  <TabsTrigger value="monitoring">모니터링</TabsTrigger>
  {!isNew && <TabsTrigger value="executions">실행 이력</TabsTrigger>}
</TabsList>

{/* ... existing tabs ... */}

<TabsContent value="monitoring">
  <JobMonitoringTab isEditing={isEditing} form={form} />
</TabsContent>
```

- [ ] **Step 2: JobOverviewTab에 트리거 타입 표시 추가**

읽기 전용 뷰의 "기본 정보" 섹션에 트리거 타입 표시 추가:

```typescript
<ReadonlyCard
  label="트리거"
  value={
    <Badge variant="outline">
      {job.triggerType === 'SCHEDULE' ? '스케줄' : job.triggerType === 'ANOMALY' ? '이상 감지' : '스케줄 + 이상 감지'}
    </Badge>
  }
/>
```

편집 모드에 트리거 타입 셀렉트 추가:

```typescript
<div className="space-y-2">
  <Label>트리거 타입</Label>
  <Select
    value={watch('triggerType') ?? 'SCHEDULE'}
    onValueChange={(v) => setValue('triggerType', v as any)}
  >
    <SelectTrigger>
      <SelectValue />
    </SelectTrigger>
    <SelectContent>
      <SelectItem value="SCHEDULE">스케줄 (Cron만)</SelectItem>
      <SelectItem value="ANOMALY">이상 감지만</SelectItem>
      <SelectItem value="BOTH">스케줄 + 이상 감지</SelectItem>
    </SelectContent>
  </Select>
  <p className="text-xs text-muted-foreground">
    ANOMALY: 이상 감지 시에만 리포트 생성. BOTH: 정기 리포트 + 이상 시 즉시 리포트.
  </p>
</div>
```

- [ ] **Step 3: 프론트엔드 빌드 + 타입체크 실행**

Run: `cd apps/firehub-web && pnpm typecheck && pnpm build`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx
git commit -m "feat(proactive): 스마트 작업 편집 페이지에 모니터링 탭 통합 (Phase 7-4)"
```

---

### Task 10: 통합 검증

**Files:** (검증만, 수정 없음)

- [ ] **Step 1: 백엔드 전체 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test`
Expected: PASS (모든 기존 + 신규 테스트 통과)

- [ ] **Step 2: 프론트엔드 빌드 + 린트 + 타입체크 실행**

Run: `cd apps/firehub-web && pnpm typecheck && pnpm lint && pnpm build`
Expected: PASS

- [ ] **Step 3: 개발 서버에서 수동 검증**

Run: `pnpm dev`

검증 항목:
1. 스마트 작업 생성 시 "모니터링" 탭이 표시되는지 확인
2. 이상 감지 활성화 토글 동작 확인
3. 시스템 메트릭 추가/삭제 동작 확인
4. 데이터셋 메트릭 추가 + SQL 입력 동작 확인
5. 감도 변경 + 재알림 방지 시간 설정 동작 확인
6. 트리거 타입 변경 동작 확인
7. 작업 저장 후 읽기 전용 뷰에서 모니터링 설정이 올바르게 표시되는지 확인
8. 기존 스마트 작업(이상 감지 미설정)이 정상 동작하는지 확인 (하위 호환)

- [ ] **Step 4: Playwright 스크린샷 (선택)**

모니터링 탭 편집 화면의 스크린샷을 `snapshots/` 폴더에 저장하여 디자인 확인.

- [ ] **Step 5: 커밋 (수정 사항이 있는 경우)**

통합 검증에서 발견된 이슈를 수정하고 커밋.

---

## 검증 기준 체크리스트

- [ ] 시스템 메트릭 폴링 + metric_snapshot 저장 동작
- [ ] 데이터셋 메트릭 SQL 쿼리 실행 + 저장 동작
- [ ] 이상 감지 시 스마트 작업 자동 실행 확인
- [ ] cooldown 중복 방지 동작
- [ ] sensitivity 변경에 따른 감지 임계값 변화 확인
- [ ] 히스토리 부족 시 (7개 미만) 감지 보류 확인
- [ ] 모니터링 탭 UI 동작 (메트릭 추가/삭제, 감도, cooldown)
- [ ] 트리거 타입 (SCHEDULE/ANOMALY/BOTH) 전환 동작
- [ ] 기존 스마트 작업 하위 호환 (trigger_type 기본값 SCHEDULE)
- [ ] 백엔드 통합 테스트 전체 통과
- [ ] 프론트엔드 빌드 + 타입체크 + 린트 통과

# 대시보드 실행 이력 TTL + Stale 카드 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `pipeline_execution` 90일 TTL 스케줄러 + `trigger_event` FK CASCADE 마이그레이션 + "주의 필요" 카드에서 stale 데이터셋 블록 제거.

**Architecture:** Spring `@Scheduled` Job 한 개 + Flyway 마이그레이션 한 개 + `DashboardService.getAttentionItems`의 stale 계산 블록 삭제. 변경 사항을 task 별로 staging 하고 **마지막에 1회 커밋** (사용자 지침).

**Tech Stack:** Spring Boot, Flyway, jOOQ, JUnit5 `IntegrationTestBase`, PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-05-19-dashboard-execution-ttl-design.md`

**Related Issues:** #223 (축소 진행 후 close), #224 (폐기 close)

---

## File Structure

**신규**
- `apps/firehub-api/src/main/resources/db/migration/V59__trigger_event_cascade_on_execution_delete.sql`
- `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJob.java`
- `apps/firehub-api/src/test/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJobTest.java`

**수정**
- `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java` (라인 401–457 stale 블록 삭제)
- `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/dto/AttentionItemResponse.java` (주석에서 DATASET_STALE 제거)
- `apps/firehub-api/src/test/java/com/smartfirehub/dashboard/service/DashboardHealthTest.java` (DATASET_STALE 단언 제거/조정)
- `apps/firehub-api/src/main/resources/application.yml` (`flyway.baseline-version: 58` → `59`)

**프론트엔드 영향 없음**: 응답이 그저 더 짧아질 뿐. `AttentionItemResponse.type`은 `string` 그대로. `datasetHealth.stale` 통계 카운터는 별도 메트릭(`DashboardStatsResponse`)이며 본 작업 범위 외이므로 그대로 유지. E2E도 DATASET_STALE을 직접 단언하는 spec 없음 (`grep -rn DATASET_STALE apps/firehub-web` 결과 0건).

---

## Task 1: V59 마이그레이션 + baseline 갱신

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V59__trigger_event_cascade_on_execution_delete.sql`
- Modify: `apps/firehub-api/src/main/resources/application.yml`

- [ ] **Step 1: 마이그레이션 SQL 작성**

```sql
-- V59__trigger_event_cascade_on_execution_delete.sql
-- pipeline_execution TTL 정리 시 자식 trigger_event 행이 자동 삭제되도록
-- trigger_event.execution_id FK 를 ON DELETE CASCADE 로 변경 (#223)

ALTER TABLE trigger_event
    DROP CONSTRAINT IF EXISTS trigger_event_execution_id_fkey;

ALTER TABLE trigger_event
    ADD CONSTRAINT trigger_event_execution_id_fkey
        FOREIGN KEY (execution_id) REFERENCES pipeline_execution(id) ON DELETE CASCADE;
```

- [ ] **Step 2: baseline-version 업데이트**

`apps/firehub-api/src/main/resources/application.yml` 의 라인 7:

```yaml
    baseline-version: 58
```
을

```yaml
    baseline-version: 59
```
로 변경.

- [ ] **Step 3: 로컬 DB에 적용 + 제약조건 확인**

```
./gradlew :flywayMigrate 2>/dev/null || ./gradlew bootRun --args='--spring.profiles.active=local' &
```

또는 `pnpm dev:full` 이 이미 떠 있다면 재기동 (Flyway가 startup 시 V59 적용).

확인:

```
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -tAc \
  "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='trigger_event_execution_id_fkey';"
```

Expected: `FOREIGN KEY (execution_id) REFERENCES pipeline_execution(id) ON DELETE CASCADE`

- [ ] **Step 4: stage (커밋 X — 마지막에 1회 커밋)**

```
git add apps/firehub-api/src/main/resources/db/migration/V59__trigger_event_cascade_on_execution_delete.sql apps/firehub-api/src/main/resources/application.yml
```

---

## Task 2: PipelineExecutionTtlJob 구현 — TDD

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJob.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJobTest.java`

### Step 1: 실패하는 테스트 작성

`apps/firehub-api/src/test/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJobTest.java` 신규:

```java
package com.smartfirehub.dashboard.job;

import static com.smartfirehub.jooq.Tables.PIPELINE;
import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;
import static com.smartfirehub.jooq.Tables.TRIGGER_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.IntegrationTestBase;
import java.time.LocalDateTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * pipeline_execution TTL 정리 Job 통합 테스트.
 *
 * - 90일 초과 + COMPLETED 행만 삭제, FAILED 는 보존.
 * - 윈도우 내(89일 등) COMPLETED 행은 보존.
 * - trigger_event 자식 행이 FK CASCADE 로 함께 삭제되는지 검증 (V59 의존).
 */
class PipelineExecutionTtlJobTest extends IntegrationTestBase {

  @Autowired private PipelineExecutionTtlJob job;
  @Autowired private DSLContext dsl;

  private Long pipelineId;

  @BeforeEach
  void seedPipeline() {
    pipelineId =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "ttl-test-" + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_AT, LocalDateTime.now())
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();
  }

  /** 90일 보존 정책 — created_at < (now - days) AND status = COMPLETED 행만 삭제. */
  @Test
  void runOnce_deletesCompletedRowsOlderThanRetention() {
    Long oldCompleted = insertExecution(LocalDateTime.now().minusDays(100), "COMPLETED");
    Long oldFailed = insertExecution(LocalDateTime.now().minusDays(100), "FAILED");
    Long recentCompleted = insertExecution(LocalDateTime.now().minusDays(89), "COMPLETED");

    ReflectionTestUtils.setField(job, "retentionDays", 90);
    int deleted = job.runOnce();

    assertThat(deleted).isEqualTo(1);
    assertThat(executionExists(oldCompleted)).isFalse();
    assertThat(executionExists(oldFailed)).isTrue();
    assertThat(executionExists(recentCompleted)).isTrue();
  }

  /** retentionDays override 검증 — 30일로 설정 시 89일 행도 삭제. */
  @Test
  void runOnce_respectsRetentionDaysOverride() {
    Long execId = insertExecution(LocalDateTime.now().minusDays(89), "COMPLETED");
    ReflectionTestUtils.setField(job, "retentionDays", 30);

    job.runOnce();

    assertThat(executionExists(execId)).isFalse();
  }

  /** CASCADE — trigger_event 자식 행이 부모 삭제 시 함께 제거. V59 마이그레이션 의존. */
  @Test
  void runOnce_cascadesTriggerEventChild() {
    Long execId = insertExecution(LocalDateTime.now().minusDays(100), "COMPLETED");
    Long eventId =
        dsl.insertInto(TRIGGER_EVENT)
            .set(TRIGGER_EVENT.PIPELINE_ID, pipelineId)
            .set(TRIGGER_EVENT.EXECUTION_ID, execId)
            .set(TRIGGER_EVENT.EVENT_TYPE, "TEST")
            .set(TRIGGER_EVENT.CREATED_AT, LocalDateTime.now())
            .returning(TRIGGER_EVENT.ID)
            .fetchOne()
            .getId();

    ReflectionTestUtils.setField(job, "retentionDays", 90);
    job.runOnce();

    assertThat(executionExists(execId)).isFalse();
    assertThat(
            dsl.fetchExists(
                dsl.selectFrom(TRIGGER_EVENT).where(TRIGGER_EVENT.ID.eq(eventId))))
        .isFalse();
  }

  private Long insertExecution(LocalDateTime createdAt, String status) {
    return dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, pipelineId)
        .set(PIPELINE_EXECUTION.STATUS, status)
        .set(PIPELINE_EXECUTION.CREATED_AT, createdAt)
        .returning(PIPELINE_EXECUTION.ID)
        .fetchOne()
        .getId();
  }

  private boolean executionExists(Long id) {
    return dsl.fetchExists(
        dsl.selectFrom(PIPELINE_EXECUTION).where(PIPELINE_EXECUTION.ID.eq(id)));
  }
}
```

> 참고: `TRIGGER_EVENT` 테이블의 컬럼 — `pipeline_id` 는 NOT NULL, `trigger_id` 는 NOT NULL REFERENCES `pipeline_trigger`. 위 테스트에서는 자식 CASCADE 만 검증이 목적이므로 `trigger_id` 컬럼을 직접 두는 게 NOT NULL 제약 위반이 될 수 있다. 만약 그렇다면 trigger row 도 시드하거나 `TRIGGER_EVENT.TRIGGER_ID` 컬럼이 nullable 인지 확인 후 보정. **이는 테스트 작성 시 실제 jOOQ 메타데이터로 확인할 것.**

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```
./gradlew test --tests "com.smartfirehub.dashboard.job.PipelineExecutionTtlJobTest"
```

Expected: FAIL with `Cannot resolve symbol PipelineExecutionTtlJob` 등 컴파일 에러.

- [ ] **Step 3: PipelineExecutionTtlJob 구현**

Create `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJob.java`:

```java
package com.smartfirehub.dashboard.job;

import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pipeline_execution 90일 이상 누적된 COMPLETED 행을 자동 정리.
 *
 * - 매일 자정 (KST) 실행. 둘 다 env override 가능.
 * - 정책: created_at < (now - retentionDays) AND status = 'COMPLETED'.
 * - FAILED 는 보존 — 디버깅·재시도 단서.
 * - 자식 trigger_event 는 FK ON DELETE CASCADE (V59) 로 자동 정리.
 * - 운영자가 손으로 DELETE 치는 작업을 제거 (#223).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineExecutionTtlJob {

  private final DSLContext dsl;

  @Value("${firehub.execution.ttl.days:90}")
  private int retentionDays;

  @Scheduled(cron = "${firehub.execution.ttl.cron:0 0 0 * * *}")
  public void runScheduled() {
    runOnce();
  }

  /** 테스트·수동 호출용. 삭제 행 수 반환. */
  public int runOnce() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
    int deleted =
        dsl.deleteFrom(PIPELINE_EXECUTION)
            .where(PIPELINE_EXECUTION.CREATED_AT.lt(cutoff))
            .and(PIPELINE_EXECUTION.STATUS.eq("COMPLETED"))
            .execute();
    log.info(
        "PipelineExecutionTtl: deleted {} rows older than {} days (cutoff={})",
        deleted, retentionDays, cutoff);
    return deleted;
  }
}
```

- [ ] **Step 4: 테스트 PASS 확인**

```
./gradlew test --tests "com.smartfirehub.dashboard.job.PipelineExecutionTtlJobTest"
```

Expected: 3 tests PASS.

만약 CASCADE 테스트에서 `trigger_id` NOT NULL 에러 발생 시 — `pipeline_trigger` 행을 먼저 시드하도록 `seedPipeline()` 보강.

- [ ] **Step 5: stage**

```
git add \
  apps/firehub-api/src/main/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJob.java \
  apps/firehub-api/src/test/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJobTest.java
```

---

## Task 3: DashboardService stale 블록 제거

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java` (라인 401–457)

- [ ] **Step 1: stale 블록 삭제**

`apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java` 의 다음 영역을 **통째로 삭제**:

```java
    // 2. Stale source datasets: 24h+ no successful import
    // WARNING: 24h~72h, CRITICAL: 72h+
    var staleDatasets =
        dsl.select(D_ID, D_NAME, field("last_import.last_import_at", LocalDateTime.class))
            .from(DATASET)
            .leftJoin(
                dsl.select(
                        AL_RESOURCE_ID.as("dataset_rid"), max(AL_ACTION_TIME).as("last_import_at"))
                    .from(AUDIT_LOG)
                    .where(
                        AL_ACTION_TYPE
                            .eq("IMPORT")
                            .and(AL_RESOURCE.eq("dataset"))
                            .and(AL_RESULT.eq("SUCCESS")))
                    .groupBy(AL_RESOURCE_ID)
                    .asTable("last_import"))
            .on(D_ID.cast(String.class).eq(field("last_import.dataset_rid", String.class)))
            .where(
                D_DATASET_TYPE
                    .eq("SOURCE")
                    .and(
                        // no import at all, or last import older than 24h
                        field("last_import.last_import_at", LocalDateTime.class)
                            .isNull()
                            .or(
                                field("last_import.last_import_at", LocalDateTime.class)
                                    .lessOrEqual(twentyFourHoursAgo)))
                    // exclude brand-new datasets (created <24h ago with no import — not yet stale)
                    .and(
                        D_CREATED_AT
                            .lessOrEqual(twentyFourHoursAgo)
                            .or(
                                field("last_import.last_import_at", LocalDateTime.class)
                                    .isNotNull())))
            .fetch();

    for (Record r : staleDatasets) {
      Long datasetId = r.get(D_ID);
      String datasetName = r.get(D_NAME);
      LocalDateTime lastImportAt = r.get(field("last_import.last_import_at", LocalDateTime.class));

      boolean isCritical = lastImportAt == null || lastImportAt.isBefore(seventyTwoHoursAgo);
      String severity = isCritical ? "CRITICAL" : "WARNING";

      String description =
          lastImportAt != null ? "마지막 갱신: " + formatTimeAgo(lastImportAt, now) : "임포트 이력 없음";

      items.add(
          new AttentionItemResponse(
              "DATASET_STALE",
              severity,
              "데이터셋 '" + datasetName + "' 오래됨",
              description,
              datasetId,
              "DATASET",
              lastImportAt != null ? lastImportAt : now));
    }
```

- [ ] **Step 2: 메서드 상단 미사용 변수 정리**

`seventyTwoHoursAgo` 가 stale 블록에서만 쓰였다면 라인 334 의 선언을 제거. `twentyFourHoursAgo` 는 #3(failed imports) 블록에서 여전히 사용 → 유지.

확인 방법: 파일 안에서 `seventyTwoHoursAgo` 검색해 다른 참조가 없으면 삭제.

`twentyFourHoursAgo` 도 #3 블록에서 쓰이므로 그대로 유지.

- [ ] **Step 3: AttentionItemResponse 주석 갱신**

`apps/firehub-api/src/main/java/com/smartfirehub/dashboard/dto/AttentionItemResponse.java` 라인 6:

```java
    String type, // PIPELINE_FAILED, DATASET_STALE, IMPORT_FAILED
```
을

```java
    String type, // PIPELINE_FAILED, IMPORT_FAILED
```
로 변경.

- [ ] **Step 4: 미사용 import 정리**

스태틱 import 들 중 stale 블록에서만 쓰던 것:
- `org.jooq.impl.DSL.max` — `audit_log` 의 max 집계는 stale 블록 전용. 다른 곳에서 안 쓰면 제거.
- `D_DATASET_TYPE`, `D_CREATED_AT` — stale 블록 전용일 가능성 큼.
- `AL_RESOURCE_ID` — stale 블록의 left join 에서만 쓰임 (#3 블록은 `AL_RESOURCE_ID_AS_LONG` 사용).
- `AL_RESULT` — #3 블록에서도 사용 (`AL_RESULT.eq("FAILURE")`) → 유지.

각 import 에 대해 파일 안에서 검색 후 다른 참조가 없으면 삭제. IDE/`./gradlew build` 가 unused import 경고를 통해 안내.

- [ ] **Step 5: 컴파일 확인**

```
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: stage**

```
git add \
  apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java \
  apps/firehub-api/src/main/java/com/smartfirehub/dashboard/dto/AttentionItemResponse.java
```

---

## Task 4: DashboardHealthTest 갱신

**Files:**
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/dashboard/service/DashboardHealthTest.java`

기존 테스트 3건이 DATASET_STALE 단언을 가지고 있음 — 라인 263–298 부근.

- [ ] **Step 1: 삭제 대상 테스트 식별**

```
grep -n "getAttentionItems_containsStaleDatasets\|getAttentionItems_freshDatasetNotInList\|DATASET_STALE" \
  apps/firehub-api/src/test/java/com/smartfirehub/dashboard/service/DashboardHealthTest.java
```

확인 후:

- `getAttentionItems_containsStaleDatasets()` — **삭제** (DATASET_STALE 자체가 없어졌으므로).
- `getAttentionItems_freshDatasetNotInList()` — **삭제 또는 변환**. fresh 데이터셋이 stale 카드에 안 나오는 검증인데 stale 자체가 없으니 의미 없음. **삭제**.

- [ ] **Step 2: 두 메서드 통째 제거**

`getAttentionItems_containsStaleDatasets()` 와 `getAttentionItems_freshDatasetNotInList()` 두 `@Test` 메서드(메서드 시그니처 + 본문 + 닫는 `}`)를 파일에서 삭제.

만약 다른 테스트(`getAttentionItems_sortedBySeverityThenDate` 등)가 stale 데이터셋 시드를 통해 정렬을 검증했다면, 그 테스트는 failedPipeline / failedImport 시드만 가지고 동작하도록 정렬 검증 시드를 보강 (CRITICAL vs WARNING 정렬이 검증 가능한지 확인). 만약 stale 의존이라면 메서드 자체를 삭제하거나 시드를 바꿈.

> 작업 시 해당 테스트를 먼저 읽고 의존 관계를 확인할 것. 단순 삭제로 안전한 경우 vs 시드 변경이 필요한 경우 구분.

- [ ] **Step 3: 시드 코드의 stale 데이터셋 정리**

`@BeforeEach` 또는 `setUp` 메서드에서 `staleDatasetId` / `criticalStaleDatasetId` 시드 코드가 있을 수 있음.

**다만** `datasetHealth().stale()` 통계 (라인 222–232 부근 `getDashboardStats_returnsCounts` 등)에서 이 시드가 여전히 필요하므로 **삭제하지 않고 유지**. stale 데이터셋이 stats 카운터에는 잡히지만 attention items 에는 안 나온다 — 본 작업의 의도와 일치.

따라서 시드는 **그대로 유지**, attention 단언 테스트만 삭제.

- [ ] **Step 4: 테스트 실행**

```
./gradlew test --tests "com.smartfirehub.dashboard.service.DashboardHealthTest"
```

Expected: 남은 테스트 전부 PASS. `containsStaleDatasets` / `freshDatasetNotInList` 는 더 이상 존재하지 않음.

- [ ] **Step 5: stage**

```
git add apps/firehub-api/src/test/java/com/smartfirehub/dashboard/service/DashboardHealthTest.java
```

---

## Task 5: 통합 빌드 + 단일 커밋

- [ ] **Step 1: 전체 백엔드 빌드 + 테스트**

```
./gradlew build
```

Expected: BUILD SUCCESSFUL — 전체 테스트 통과.

만약 다른 테스트가 staged 변경의 영향을 받으면 그 자리에서 디버그 후 fix (해당 task 의 staged 파일에 직접 수정 후 다시 `git add`).

- [ ] **Step 2: jOOQ 코드젠 (FK 변경 반영)**

V59 적용된 DB 가 떠 있는 상태에서:

```
./gradlew generateJooqSchemaSource
```

Expected: BUILD SUCCESSFUL. 산출물이 `src/main/generated` 에 갱신됨. 변경이 있으면 함께 staging:

```
git add apps/firehub-api/src/main/generated/
```

생성 산출물은 보통 FK CASCADE 변경만으로는 변하지 않을 수 있음. 차이 없으면 그대로 진행.

- [ ] **Step 3: 프론트엔드 typecheck 회귀**

타입 변경 없지만 프론트엔드 회귀 확인:

```
pnpm --filter @smart-fire-hub/firehub-web typecheck
```

Expected: PASS.

- [ ] **Step 4: 최종 staged 확인**

```
git status --short
```

Expected (예시):

```
A  apps/firehub-api/src/main/resources/db/migration/V59__trigger_event_cascade_on_execution_delete.sql
M  apps/firehub-api/src/main/resources/application.yml
A  apps/firehub-api/src/main/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJob.java
A  apps/firehub-api/src/test/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJobTest.java
M  apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java
M  apps/firehub-api/src/main/java/com/smartfirehub/dashboard/dto/AttentionItemResponse.java
M  apps/firehub-api/src/test/java/com/smartfirehub/dashboard/service/DashboardHealthTest.java
```

- [ ] **Step 5: 단일 커밋**

```
git commit -m "$(cat <<'EOF'
feat(dashboard): pipeline_execution 90일 TTL + stale 카드 제거 (refs #223, closes #224)

- V59: trigger_event.execution_id FK ON DELETE CASCADE (TTL 정리 시 자식 자동 삭제)
- PipelineExecutionTtlJob: 매일 자정 COMPLETED + 90일 경과 행 삭제 (env override)
- DashboardService.getAttentionItems: stale 데이터셋 블록 제거 (약한 시그널/저해상도)
- AttentionItemResponse 주석 갱신, DashboardHealthTest stale 단언 제거
- application.yml baseline-version 58 → 59

acknowledge 컬럼·UI는 보류 (isResolved 로 대부분 케이스 커버).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Pre-commit 훅이 변경 영역에 따라 E2E + gradle test 를 돌릴 수 있음. 통과해야 commit 확정.

- [ ] **Step 6: 이슈 처리**

```
gh issue comment 223 --body "구현 완료. 축소판으로 진행: TTL 스케줄러 + trigger_event FK CASCADE. acknowledge 컬럼·UI 부분은 isResolved 로 대부분 케이스 커버됨을 확인 후 보류. 커밋: <SHA>. 스펙: docs/superpowers/specs/2026-05-19-dashboard-execution-ttl-design.md"
gh issue close 223 --reason completed

gh issue comment 224 --body "폐기. stale 데이터셋 알림은 임포트 실패와 중복되거나 정상 운영(일회성·주간 갱신) 케이스 분류 불가능한 저해상도 시그널로 판단. 대신 \"주의 필요\" 카드에서 stale 블록 자체 제거 (commit <SHA>). 스펙: docs/superpowers/specs/2026-05-19-dashboard-execution-ttl-design.md"
gh issue close 224 --reason "not planned"
```

`<SHA>` 는 Step 5 커밋 SHA 로 치환.

---

## Out of Scope (스펙과 일치)

- `pipeline_execution.acknowledged_at` 컬럼 / API / UI
- `dataset.expected_refresh_interval_minutes` / `dismissed_attention` 테이블
- audit_log TTL
- 스케줄 누락(cron 예정 vs 실제 실행) 알림 — 시스템에 스케줄 개념 없음

본 plan 의 어떤 task 도 이 항목들을 포함하지 않는다.

# Phase 7-4/7-5 잔여 작업 완료 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 7-4 이상 탐지(70%→100%)와 7-5 비주얼 빌더(85%→100%)를 완료한다.

**Architecture:** 7-4는 백엔드(DB 마이그레이션 + 알림 연결 + dataset 메트릭 수집) + 프론트엔드(버그 수정 + 커스텀 메트릭 모달 + 이력 UI). 7-5는 프론트엔드만(DnD 개선 + 미리보기). 두 트랙은 완전히 독립적이므로 병렬 가능.

**Tech Stack:** Spring Boot 3.4 + jOOQ, React 19 + TypeScript, TanStack Query, shadcn/ui, @dnd-kit, Playwright E2E

**Spec:** `docs/superpowers/specs/2026-04-06-phase-7-4-7-5-completion-design.md`

---

## 파일 구조

### 7-4 백엔드 (생성/수정)

| 파일 | 역할 |
|------|------|
| Create: `apps/firehub-api/src/main/resources/db/migration/V47__create_anomaly_event.sql` | anomaly_event 테이블 |
| Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/AnomalyEventRepository.java` | 이상 이벤트 CRUD |
| Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java` | 이벤트 저장 + SSE 알림 |
| Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricPollerService.java` | dataset 메트릭 수집 |
| Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/controller/ProactiveJobController.java` | anomaly-events 엔드포인트 |
| Modify: `apps/firehub-api/src/main/resources/application.yml` | baseline-version 47 |

### 7-4 프론트엔드 (수정)

| 파일 | 역할 |
|------|------|
| Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx` | 버그 수정 + 커스텀 메트릭 모달 + 이력 테이블 |
| Modify: `apps/firehub-web/src/api/proactive.ts` | AnomalyEventRecord 타입 + API 함수 |
| Modify: `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts` | useAnomalyEvents 훅 + useDatasets 연동 |

### 7-5 프론트엔드 (수정)

| 파일 | 역할 |
|------|------|
| Modify: `apps/firehub-web/src/pages/ai-insights/hooks/useSectionTree.ts` | moveSectionInTree 개선 |
| Modify: `apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx` | 타입별 가이드 미리보기 |

### E2E 테스트

| 파일 | 역할 |
|------|------|
| Modify: `apps/firehub-web/e2e/factories/ai-insight.factory.ts` | anomaly event 팩토리 |
| Modify: `apps/firehub-web/e2e/fixtures/ai-insight.fixture.ts` | anomaly 관련 fixture |
| Modify: `apps/firehub-web/e2e/pages/ai-insights/job-detail.spec.ts` | 모니터링 탭 E2E |
| Modify: `apps/firehub-web/e2e/pages/ai-insights/template-detail.spec.ts` | 빌더 DnD + 미리보기 E2E |

---

## Task 1: anomaly_event 테이블 + Repository (백엔드)

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V47__create_anomaly_event.sql`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/AnomalyEventRepository.java`
- Modify: `apps/firehub-api/src/main/resources/application.yml`

- [ ] **Step 1: V47 마이그레이션 작성**

```sql
-- V47__create_anomaly_event.sql
-- 이상 탐지 이벤트 이력 저장 테이블
CREATE TABLE IF NOT EXISTS anomaly_event (
    id              BIGSERIAL       PRIMARY KEY,
    job_id          BIGINT          NOT NULL REFERENCES proactive_job(id) ON DELETE CASCADE,
    metric_id       VARCHAR(100)    NOT NULL,
    metric_name     VARCHAR(200)    NOT NULL,
    current_value   DOUBLE PRECISION NOT NULL,
    mean            DOUBLE PRECISION NOT NULL,
    stddev          DOUBLE PRECISION NOT NULL,
    deviation       DOUBLE PRECISION NOT NULL,
    sensitivity     VARCHAR(20)     NOT NULL,
    detected_at     TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_anomaly_event_job_detected
    ON anomaly_event (job_id, detected_at DESC);
```

- [ ] **Step 2: baseline-version 업데이트**

`apps/firehub-api/src/main/resources/application.yml`에서 `baseline-version: 46`을 `baseline-version: 47`로 변경.

- [ ] **Step 3: AnomalyEventRepository 작성**

```java
package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.dto.AnomalyEvent;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import static org.jooq.impl.DSL.*;

/**
 * 이상 탐지 이벤트 저장/조회 Repository.
 * anomaly_event 테이블은 jOOQ 코드젠 대상이 아니므로 raw DSL 사용.
 */
@Repository
@RequiredArgsConstructor
public class AnomalyEventRepository {

    private final DSLContext dsl;

    /** 이상 탐지 이벤트를 DB에 저장한다. */
    public void save(AnomalyEvent event) {
        dsl.insertInto(table("anomaly_event"))
            .set(field("job_id"), event.jobId())
            .set(field("metric_id"), event.metricId())
            .set(field("metric_name"), event.metricName())
            .set(field("current_value"), event.currentValue())
            .set(field("mean"), event.mean())
            .set(field("stddev"), event.stddev())
            .set(field("deviation"), event.deviation())
            .set(field("sensitivity"), event.sensitivity())
            .set(field("detected_at"), LocalDateTime.now())
            .execute();
    }

    /** 특정 작업의 이상 탐지 이벤트를 최근 순으로 조회한다. */
    public List<AnomalyEventRecord> findByJobId(Long jobId, int limit) {
        return dsl.select(
                field("id", Long.class),
                field("job_id", Long.class),
                field("metric_id", String.class),
                field("metric_name", String.class),
                field("current_value", Double.class),
                field("mean", Double.class),
                field("stddev", Double.class),
                field("deviation", Double.class),
                field("sensitivity", String.class),
                field("detected_at", LocalDateTime.class))
            .from(table("anomaly_event"))
            .where(field("job_id").eq(jobId))
            .orderBy(field("detected_at").desc())
            .limit(limit)
            .fetch(this::toRecord);
    }

    private AnomalyEventRecord toRecord(Record r) {
        return new AnomalyEventRecord(
            r.get(field("id", Long.class)),
            r.get(field("job_id", Long.class)),
            r.get(field("metric_id", String.class)),
            r.get(field("metric_name", String.class)),
            r.get(field("current_value", Double.class)),
            r.get(field("mean", Double.class)),
            r.get(field("stddev", Double.class)),
            r.get(field("deviation", Double.class)),
            r.get(field("sensitivity", String.class)),
            r.get(field("detected_at", LocalDateTime.class)));
    }

    /** 이상 탐지 이벤트 응답 DTO */
    public record AnomalyEventRecord(
        Long id, Long jobId, String metricId, String metricName,
        double currentValue, double mean, double stddev, double deviation,
        String sensitivity, LocalDateTime detectedAt) {}
}
```

- [ ] **Step 4: jOOQ 코드젠 실행 (anomaly_event 테이블 생성 반영)**

DB가 실행 중인 상태에서:
```bash
cd apps/firehub-api && ./gradlew generateJooqSchemaSource
```

참고: anomaly_event는 jOOQ 코드젠 대상이 될 수 있으나, Repository에서 raw DSL로도 동작한다. 코드젠 후 생성된 테이블 클래스가 있으면 활용해도 좋다.

- [ ] **Step 5: 빌드 확인**

```bash
cd apps/firehub-api && ./gradlew build -x test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V47__create_anomaly_event.sql \
        apps/firehub-api/src/main/java/com/smartfirehub/proactive/repository/AnomalyEventRepository.java \
        apps/firehub-api/src/main/resources/application.yml
git commit -m "feat(proactive): anomaly_event 테이블 + Repository 추가"
```

---

## Task 2: 알림 전달 연결 (백엔드)

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java:267-293`

- [ ] **Step 1: ProactiveJobService에 의존성 추가**

클래스 필드에 `AnomalyEventRepository`와 `NotificationService` 추가:

```java
// 기존 필드들 아래에 추가
private final AnomalyEventRepository anomalyEventRepository;
private final com.smartfirehub.notification.service.NotificationService notificationService;
```

- [ ] **Step 2: onAnomalyDetected 메서드 수정**

`ProactiveJobService.java`의 `onAnomalyDetected()` 메서드를 수정한다. 작업 실행 전에 이벤트 저장 + SSE 알림을 보낸다:

```java
@EventListener
@Async("pipelineExecutor")
public void onAnomalyDetected(AnomalyEvent event) {
    if (isInCooldown(event.jobId())) {
        log.info(
            "Anomaly detected for job {} but in cooldown, skipping (metric={})",
            event.jobId(),
            event.metricName());
        return;
    }

    log.info(
        "Anomaly detected for job {}, saving event and executing"
            + " (metric={}, value={}, deviation={})",
        event.jobId(),
        event.metricName(),
        event.currentValue(),
        event.deviation());

    // 1. 이상 탐지 이벤트를 DB에 저장
    try {
        anomalyEventRepository.save(event);
    } catch (Exception e) {
        log.warn("Failed to save anomaly event for job {}: {}", event.jobId(), e.getMessage());
    }

    // 2. SSE 알림 전송 — 사용자에게 이상 탐지를 즉시 알린다
    try {
        var notification = new com.smartfirehub.notification.dto.NotificationEvent(
            java.util.UUID.randomUUID().toString(),
            "ANOMALY_DETECTED",
            "WARNING",
            "이상 탐지",
            String.format("메트릭 '%s'에서 이상이 감지되었습니다 (%.2fσ 편차)",
                event.metricName(), event.deviation()),
            "PROACTIVE_JOB",
            event.jobId(),
            java.util.Map.of(
                "metricId", event.metricId(),
                "metricName", event.metricName(),
                "currentValue", event.currentValue(),
                "deviation", event.deviation()),
            java.time.LocalDateTime.now());
        // SseEmitterRegistry를 통해 해당 사용자에게 브로드캐스트
        notificationService.getRegistry().broadcast(event.userId(), notification);
    } catch (Exception e) {
        log.warn("Failed to send anomaly notification for job {}: {}", event.jobId(), e.getMessage());
    }

    // 3. 작업 실행 (기존 로직)
    try {
        executeJob(event.jobId(), event.userId());
        recordCooldown(event.jobId());
    } catch (Exception e) {
        log.warn("Anomaly-triggered execution failed for job {}: {}", event.jobId(), e.getMessage());
    }
}
```

참고: `NotificationService`에는 `getRegistry()` 접근자가 없을 수 있다. 그 경우 `SseEmitterRegistry`를 직접 주입하여 `registry.broadcast(userId, notification)` 호출한다.

- [ ] **Step 3: SseEmitterRegistry 직접 주입 (NotificationService에 getRegistry가 없는 경우)**

`ProactiveJobService` 생성자 필드에 `SseEmitterRegistry`를 추가:

```java
private final com.smartfirehub.notification.service.SseEmitterRegistry sseEmitterRegistry;
```

Step 2의 알림 코드에서 `notificationService.getRegistry().broadcast(...)` 대신:
```java
sseEmitterRegistry.broadcast(event.userId(), notification);
```

- [ ] **Step 4: 빌드 확인**

```bash
cd apps/firehub-api && ./gradlew build -x test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java
git commit -m "feat(proactive): 이상 탐지 이벤트 DB 저장 + SSE 알림 연결"
```

---

## Task 3: anomaly-events API 엔드포인트 (백엔드)

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/controller/ProactiveJobController.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java`

- [ ] **Step 1: ProactiveJobService에 조회 메서드 추가**

```java
/**
 * 특정 작업의 이상 탐지 이벤트 이력을 조회한다.
 * @param jobId 작업 ID
 * @param limit 최대 조회 건수
 * @return 최근 이벤트 목록 (detected_at DESC)
 */
@Transactional(readOnly = true)
public List<AnomalyEventRepository.AnomalyEventRecord> getAnomalyEvents(Long jobId, int limit) {
    return anomalyEventRepository.findByJobId(jobId, limit);
}
```

- [ ] **Step 2: Controller에 엔드포인트 추가**

`ProactiveJobController.java`에 추가:

```java
@GetMapping("/{id}/anomaly-events")
@RequirePermission("proactive:read")
public ResponseEntity<List<AnomalyEventRepository.AnomalyEventRecord>> getAnomalyEvents(
    @PathVariable Long id,
    @RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok(proactiveJobService.getAnomalyEvents(id, limit));
}
```

- [ ] **Step 3: 빌드 확인**

```bash
cd apps/firehub-api && ./gradlew build -x test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/controller/ProactiveJobController.java \
        apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java
git commit -m "feat(proactive): GET /anomaly-events 이력 조회 API 추가"
```

---

## Task 4: Dataset 메트릭 수집 로직 (백엔드)

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricPollerService.java:138-147`

- [ ] **Step 1: ExecutorClient 의존성 추가**

`MetricPollerService` 클래스에 `ExecutorClient` 필드 추가:

```java
private final com.smartfirehub.pipeline.service.executor.ExecutorClient executorClient;
```

- [ ] **Step 2: dataset 메트릭 수집 구현**

`processMetric()` 메서드의 `"dataset".equals(source)` 분기를 수정한다:

```java
} else if ("dataset".equals(source)) {
    // 데이터셋 메트릭: 사용자 정의 SQL을 executor를 통해 실행하여 숫자 1개를 수집한다
    String query = (String) metric.get("query");
    if (query == null || query.isBlank()) {
        log.warn("MetricPollerService: dataset metric '{}' has no query, skipping", metricId);
        return;
    }
    try {
        var result = executorClient.executeQuery(query, 1, true);
        if (result.rows() != null && !result.rows().isEmpty()
            && result.rows().get(0) != null && !result.rows().get(0).isEmpty()) {
            Object firstCell = result.rows().get(0).values().iterator().next();
            value = firstCell instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(firstCell));
        } else {
            log.warn("MetricPollerService: dataset metric '{}' returned no data", metricId);
            return;
        }
    } catch (Exception e) {
        log.error("MetricPollerService: failed to collect dataset metric '{}'", metricId, e);
        return;
    }
}
```

기존 `return;` 문을 제거하고 위 코드로 교체한다. `value` 변수에 할당 후 아래의 snapshot 저장 + anomaly 검출 로직이 자연스럽게 이어진다.

- [ ] **Step 3: 빌드 확인**

```bash
cd apps/firehub-api && ./gradlew build -x test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/MetricPollerService.java
git commit -m "feat(proactive): dataset 메트릭 수집 — executor를 통한 SQL 실행"
```

---

## Task 5: 시스템 메트릭 Select 버그 수정 + 커스텀 메트릭 모달 (프론트엔드)

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx`
- Modify: `apps/firehub-web/src/api/proactive.ts`

- [ ] **Step 1: 시스템 메트릭 Select 버그 진단**

`dev` 서버를 실행하고 브라우저에서 `/ai-insights/jobs/{id}` → 모니터링 탭 → 이상 탐지 활성화 → "시스템 메트릭 추가" Select를 클릭하여 버그를 재현한다.

가능한 원인:
1. Select `onValueChange`가 호출되지 않음 → Select 자체의 이벤트 문제
2. 폼 상태 초기화 시 `anomalyConfig`가 `undefined`여서 `availableSystemMetrics`가 빈 배열
3. Select 컴포넌트가 부모 폼의 `onSubmit` 이벤트와 충돌

진단 후 수정한다. 일반적인 수정: Select를 독립적인 상태로 관리하거나, `e.stopPropagation()` 추가.

- [ ] **Step 2: proactive.ts에 AnomalyEventRecord 타입 + API 함수 추가**

`apps/firehub-web/src/api/proactive.ts`에 추가:

```typescript
// === Anomaly Event History ===

/** 이상 탐지 이벤트 이력 레코드 */
export interface AnomalyEventRecord {
  id: number;
  jobId: number;
  metricId: string;
  metricName: string;
  currentValue: number;
  mean: number;
  stddev: number;
  deviation: number;
  sensitivity: string;
  detectedAt: string;
}
```

`proactiveApi` 객체에 추가:

```typescript
/** 특정 작업의 이상 탐지 이벤트 이력 조회 */
getAnomalyEvents: (jobId: number, limit = 20) =>
  client.get<AnomalyEventRecord[]>(`/proactive/jobs/${jobId}/anomaly-events`, {
    params: { limit },
  }),
```

- [ ] **Step 3: useProactiveMessages.ts에 useAnomalyEvents 훅 추가**

`apps/firehub-web/src/hooks/queries/useProactiveMessages.ts`에 추가:

KEYS 객체에:
```typescript
anomalyEvents: (jobId: number) => ['proactive', 'anomaly-events', jobId] as const,
```

훅 함수:
```typescript
/** 특정 작업의 이상 탐지 이벤트 이력을 조회한다 */
export function useAnomalyEvents(jobId: number) {
  return useQuery({
    queryKey: KEYS.anomalyEvents(jobId),
    queryFn: () => proactiveApi.getAnomalyEvents(jobId).then((r) => r.data),
    enabled: !!jobId,
  });
}
```

- [ ] **Step 4: JobMonitoringTab에 커스텀 메트릭 추가 모달 구현**

`JobMonitoringTab.tsx`에 다음을 추가:

1. import 추가: `Dialog`, `DialogContent`, `DialogHeader`, `DialogTitle`, `DialogFooter`, `Textarea`, `Database` (lucide)
2. 상태: `const [showCustomModal, setShowCustomModal] = useState(false);`
3. 모달 폼 상태: `customName`, `customDatasetId`, `customQuery`, `customInterval`
4. 데이터셋 목록: `useDatasets()` 훅 import (기존 `src/hooks/queries/useDatasets.ts`)

"시스템 메트릭 추가" Select 옆에 버튼 추가:
```tsx
<Button
  variant="outline"
  size="sm"
  className="h-8 text-xs"
  onClick={() => setShowCustomModal(true)}
>
  <Database className="h-3 w-3 mr-1" />
  커스텀 메트릭 추가
</Button>
```

모달 내용:
```tsx
<Dialog open={showCustomModal} onOpenChange={setShowCustomModal}>
  <DialogContent>
    <DialogHeader>
      <DialogTitle>커스텀 메트릭 추가</DialogTitle>
    </DialogHeader>
    <div className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="custom-name">메트릭 이름</Label>
        <Input
          id="custom-name"
          value={customName}
          onChange={(e) => setCustomName(e.target.value)}
          placeholder="예: 일일 화재 건수"
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="custom-dataset">데이터셋</Label>
        <Select value={String(customDatasetId)} onValueChange={(v) => setCustomDatasetId(Number(v))}>
          <SelectTrigger id="custom-dataset">
            <SelectValue placeholder="데이터셋 선택..." />
          </SelectTrigger>
          <SelectContent>
            {datasets.map((ds) => (
              <SelectItem key={ds.id} value={String(ds.id)}>{ds.name}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="space-y-2">
        <Label htmlFor="custom-query">SQL 쿼리 (숫자 1개 반환)</Label>
        <Textarea
          id="custom-query"
          value={customQuery}
          onChange={(e) => setCustomQuery(e.target.value)}
          placeholder="SELECT COUNT(*) FROM {테이블명} WHERE ..."
          rows={3}
          className="font-mono text-sm"
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="custom-interval">폴링 주기 (초)</Label>
        <Input
          id="custom-interval"
          type="number"
          min={60}
          value={customInterval}
          onChange={(e) => setCustomInterval(Number(e.target.value) || 600)}
        />
      </div>
    </div>
    <DialogFooter>
      <Button variant="outline" onClick={() => setShowCustomModal(false)}>취소</Button>
      <Button
        disabled={!customName || !customDatasetId || !customQuery}
        onClick={() => {
          updateAnomaly({
            metrics: [
              ...anomalyConfig.metrics,
              {
                id: crypto.randomUUID(),
                name: customName,
                source: 'dataset' as const,
                datasetId: customDatasetId,
                query: customQuery,
                pollingInterval: customInterval,
              },
            ],
          });
          setShowCustomModal(false);
          setCustomName('');
          setCustomDatasetId(0);
          setCustomQuery('');
          setCustomInterval(600);
        }}
      >
        추가
      </Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
```

- [ ] **Step 5: 이상 탐지 이력 테이블 (읽기 전용 + 편집 모드 공통)**

`JobMonitoringTab.tsx`에 이력 섹션을 추가한다. props에 `jobId: number`를 추가해야 한다 (상위 `ProactiveJobDetailPage`에서 전달).

이력 섹션 (메트릭 설정 영역 아래):
```tsx
{/* 이상 탐지 이력 — 편집/읽기 모드 공통 */}
{anomalyConfig.enabled && jobId > 0 && (
  <AnomalyHistorySection jobId={jobId} />
)}
```

같은 파일 하단에 별도 컴포넌트:
```tsx
function AnomalyHistorySection({ jobId }: { jobId: number }) {
  const { data: events = [], isLoading } = useAnomalyEvents(jobId);

  return (
    <div className="rounded-lg border p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold flex items-center gap-2">
          <AlertTriangle className="h-4 w-4" />
          최근 이상 탐지
        </h3>
        <Badge variant="secondary">{events.length}건</Badge>
      </div>

      {isLoading ? (
        <div className="text-sm text-muted-foreground">로딩 중...</div>
      ) : events.length === 0 ? (
        <div className="text-center py-6 text-sm text-muted-foreground border rounded-lg border-dashed">
          감지된 이상이 없습니다
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-xs text-muted-foreground">
                <th className="text-left py-2 pr-3">감지 시간</th>
                <th className="text-left py-2 pr-3">메트릭</th>
                <th className="text-right py-2 pr-3">현재 값</th>
                <th className="text-right py-2 pr-3">평균</th>
                <th className="text-right py-2 pr-3">편차</th>
                <th className="text-left py-2">민감도</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.id} className="border-b last:border-0">
                  <td className="py-2 pr-3 text-muted-foreground">
                    {new Date(e.detectedAt).toLocaleString('ko-KR')}
                  </td>
                  <td className="py-2 pr-3">{e.metricName}</td>
                  <td className="py-2 pr-3 text-right font-mono">{e.currentValue.toFixed(2)}</td>
                  <td className="py-2 pr-3 text-right font-mono">{e.mean.toFixed(2)}</td>
                  <td className="py-2 pr-3 text-right font-mono">{e.deviation.toFixed(2)}σ</td>
                  <td className="py-2">
                    <Badge variant="outline" className="text-xs">
                      {e.sensitivity === 'high' ? '높음' : e.sensitivity === 'medium' ? '보통' : '낮음'}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
```

import 추가: `AlertTriangle` (lucide), `useAnomalyEvents` (hooks/queries).

- [ ] **Step 6: 빌드 + 타입 체크**

```bash
cd apps/firehub-web && pnpm build
```
Expected: 성공

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx \
        apps/firehub-web/src/api/proactive.ts \
        apps/firehub-web/src/hooks/queries/useProactiveMessages.ts
git commit -m "feat(web): 시스템 메트릭 버그 수정 + 커스텀 메트릭 모달 + 이상 탐지 이력 UI"
```

---

## Task 6: 그룹 간 자유 정렬 (프론트엔드)

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/hooks/useSectionTree.ts:163-199`

- [ ] **Step 1: moveSectionInTree 함수 개선**

`useSectionTree.ts`의 `moveSectionInTree()` 함수를 전면 교체한다:

```typescript
/**
 * 섹션을 트리 내에서 이동한다.
 * - 같은 부모 내: 순서 변경
 * - 다른 부모로: 원래 위치에서 제거 → 대상 위치에 삽입
 * - 그룹 위에 드롭: 해당 그룹의 children 마지막에 추가
 */
function moveSectionInTree(
  sections: TemplateSection[],
  activeId: string,
  overId: string,
  flatItems: FlatItem[],
): TemplateSection[] {
  // 1. flatItems에서 active와 over의 parentKey를 조회한다
  const activeFlat = flatItems.find((f) => f.section.key === activeId);
  const overFlat = flatItems.find((f) => f.section.key === overId);
  if (!activeFlat || !overFlat) return sections;

  const activeSection = activeFlat.section;
  const activeParent = activeFlat.parentKey;
  const overParent = overFlat.parentKey;

  // 2. over가 group이고 active가 group이 아니면 → 그룹 children 마지막에 추가
  if (overFlat.section.type === 'group' && activeSection.type !== 'group') {
    const withoutActive = removeFromTree(sections, activeId);
    return addToParent(withoutActive, overId, { ...activeSection });
  }

  // 3. 같은 부모 내 이동
  if (activeParent === overParent) {
    if (activeParent === null) {
      // 루트 레벨에서 순서 변경
      return reorderInArray(sections, activeId, overId);
    } else {
      // 특정 그룹의 children 내에서 순서 변경
      return reorderInParent(sections, activeParent, activeId, overId);
    }
  }

  // 4. 다른 부모 간 이동: active를 제거하고 over 앞에 삽입
  const withoutActive = removeFromTree(sections, activeId);
  if (overParent === null) {
    // over가 루트 레벨 → 루트에 삽입
    return insertBeforeInArray(withoutActive, overId, activeSection);
  } else {
    // over가 특정 그룹 내 → 해당 그룹의 children에 삽입
    return insertBeforeInParent(withoutActive, overParent, overId, activeSection);
  }
}

/** 배열 내에서 activeId를 overId 앞으로 이동한다 */
function reorderInArray(items: TemplateSection[], activeId: string, overId: string): TemplateSection[] {
  const activeIdx = items.findIndex((s) => s.key === activeId);
  const overIdx = items.findIndex((s) => s.key === overId);
  if (activeIdx === -1 || overIdx === -1) return items;

  const result = [...items];
  const [moved] = result.splice(activeIdx, 1);
  const newOverIdx = result.findIndex((s) => s.key === overId);
  result.splice(newOverIdx, 0, moved);
  return result;
}

/** 특정 부모 그룹의 children 내에서 순서를 변경한다 */
function reorderInParent(
  sections: TemplateSection[],
  parentKey: string,
  activeId: string,
  overId: string,
): TemplateSection[] {
  return sections.map((s) => {
    if (s.key === parentKey && s.children) {
      return { ...s, children: reorderInArray(s.children, activeId, overId) };
    }
    if (s.children) {
      return { ...s, children: reorderInParent(s.children, parentKey, activeId, overId) };
    }
    return s;
  });
}

/** 배열에서 overId 앞에 section을 삽입한다 */
function insertBeforeInArray(
  items: TemplateSection[],
  overId: string,
  section: TemplateSection,
): TemplateSection[] {
  const result: TemplateSection[] = [];
  for (const item of items) {
    if (item.key === overId) result.push(section);
    result.push(item);
  }
  // overId를 못 찾았으면 마지막에 추가
  if (!result.some((s) => s.key === section.key)) result.push(section);
  return result;
}

/** 특정 부모 그룹의 children에서 overId 앞에 section을 삽입한다 */
function insertBeforeInParent(
  sections: TemplateSection[],
  parentKey: string,
  overId: string,
  section: TemplateSection,
): TemplateSection[] {
  return sections.map((s) => {
    if (s.key === parentKey && s.children) {
      return { ...s, children: insertBeforeInArray(s.children, overId, section) };
    }
    if (s.children) {
      return { ...s, children: insertBeforeInParent(s.children, parentKey, overId, section) };
    }
    return s;
  });
}
```

- [ ] **Step 2: moveSection 콜백에 flatItems 전달**

`useSectionTree.ts`의 `moveSection` 콜백을 수정한다:

```typescript
const moveSection = useCallback((activeId: string, overId: string) => {
  if (activeId === overId) return;
  setSections((prev) => {
    // flatItems를 현재 sections 기반으로 재계산 (prev 기준)
    const currentFlat: FlatItem[] = [];
    function walkForFlat(items: TemplateSection[], depth: number, parentKey: string | null) {
      for (const item of items) {
        currentFlat.push({ section: item, depth, parentKey });
        if (item.type === 'group' && item.children) {
          walkForFlat(item.children, depth + 1, item.key);
        }
      }
    }
    walkForFlat(prev, 0, null);

    const moved = moveSectionInTree(prev, activeId, overId, currentFlat);
    if (!validateSectionDepth(moved)) {
      toast.error('최대 3단계까지 중첩 가능합니다');
      return prev;
    }
    return moved;
  });
}, []);
```

참고: `walkForFlat`에서는 collapsed 상태를 무시하고 전체 트리를 순회한다 (이동 대상이 접힌 그룹 안에 있을 수 있으므로).

- [ ] **Step 3: 빌드 + 타입 체크**

```bash
cd apps/firehub-web && pnpm build
```
Expected: 성공

- [ ] **Step 4: 수동 검증**

브라우저에서 `/ai-insights/templates/{id}` → 빌더 탭:
1. 그룹 안의 섹션을 같은 그룹 내에서 드래그하여 순서 변경 → 성공
2. 그룹 밖의 섹션을 그룹 위에 드래그 → 그룹 children으로 삽입
3. 그룹 안의 섹션을 루트로 드래그 → 루트에 삽입
4. 3단계 초과 중첩 시도 → "최대 3단계" toast 에러

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/hooks/useSectionTree.ts
git commit -m "feat(web): 비주얼 빌더 그룹 간 자유 정렬 — DnD 개선"
```

---

## Task 7: 구조 + 가이드 미리보기 (프론트엔드)

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx`

- [ ] **Step 1: SectionPreview 전면 개선**

`SectionPreview.tsx`를 수정하여 각 섹션 타입별 리포트 레이아웃 프리뷰를 렌더링한다:

```tsx
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import type { TemplateSection } from '@/api/proactive';
import { getSectionTypeDef } from '@/lib/template-section-types';

const SAMPLE_VARIABLES: Record<string, string> = {
  date: new Date().toISOString().slice(0, 16).replace('T', ' '),
  jobName: '(작업 이름)',
  author: '(작성자)',
  templateName: '(템플릿 이름)',
  period: '(분석 기간)',
};

function substituteVariables(content: string): string {
  return content.replace(/\{\{(\w+)\}\}/g, (_, key) => SAMPLE_VARIABLES[key] ?? `{{${key}}}`);
}

/** 섹션 타입별 가이드 미리보기를 렌더링한다 */
function renderSectionPreview(section: TemplateSection, index: number, depth = 0) {
  const def = getSectionTypeDef(section.type);
  const indent = depth * 20;

  // 구분선
  if (section.type === 'divider') {
    return <Separator key={section.key || index} className="my-3" style={{ marginLeft: indent }} />;
  }

  // 그룹: 제목 + children 재귀
  if (section.type === 'group') {
    return (
      <div key={section.key || index} className="space-y-2" style={{ marginLeft: indent }}>
        <div className="flex items-center gap-2 pt-2">
          <span className="text-base font-semibold">{section.label}</span>
          {section.instruction && (
            <span className="text-xs text-muted-foreground italic truncate max-w-xs">
              {section.instruction}
            </span>
          )}
        </div>
        {section.children?.map((child, i) => renderSectionPreview(child, i, depth + 1))}
      </div>
    );
  }

  // 정적 섹션: content 변수 치환 후 실제 텍스트 표시
  if (section.static && section.content) {
    return (
      <div
        key={section.key || index}
        className={`rounded-lg border-l-3 p-3 ${def?.color ?? 'border-l-gray-500'} bg-muted/30`}
        style={{ marginLeft: indent }}
      >
        <div className="flex items-center gap-2 mb-1">
          <span>{def?.icon}</span>
          <span className="text-sm font-medium">{section.label}</span>
          <Badge variant="secondary" className="text-[10px]">정적</Badge>
        </div>
        <p className="text-sm whitespace-pre-line">{substituteVariables(section.content)}</p>
      </div>
    );
  }

  // AI 생성 섹션: 타입별 플레이스홀더 렌더링
  return (
    <div
      key={section.key || index}
      className={`rounded-lg border-l-3 p-3 ${def?.color ?? 'border-l-gray-500'} bg-muted/20`}
      style={{ marginLeft: indent }}
    >
      <div className="flex items-center gap-2 mb-2">
        <span>{def?.icon}</span>
        <span className="text-sm font-medium">{section.label}</span>
        {section.required && <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />}
        <Badge variant="outline" className="text-[10px] ml-auto">{section.type}</Badge>
      </div>

      {/* 타입별 플레이스홀더 */}
      {renderTypePlaceholder(section.type)}

      {/* instruction 가이드 표시 */}
      {section.instruction && (
        <div className="mt-2 pt-2 border-t border-dashed text-xs text-muted-foreground italic">
          AI 지시: {section.instruction}
        </div>
      )}
    </div>
  );
}

/** 섹션 타입별 플레이스홀더 UI */
function renderTypePlaceholder(type: string) {
  switch (type) {
    case 'text':
      return (
        <div className="space-y-1.5">
          <div className="h-3 bg-muted rounded w-full" />
          <div className="h-3 bg-muted rounded w-11/12" />
          <div className="h-3 bg-muted rounded w-4/5" />
        </div>
      );

    case 'cards':
      return (
        <div className="flex gap-2">
          {[1, 2, 3].map((i) => (
            <div key={i} className="flex-1 rounded border p-2 text-center">
              <div className="text-lg font-bold text-muted-foreground">--</div>
              <div className="text-[10px] text-muted-foreground">지표 {i}</div>
            </div>
          ))}
        </div>
      );

    case 'list':
      return (
        <div className="space-y-1 text-xs text-muted-foreground">
          <div className="flex items-center gap-2"><span>•</span><div className="h-2.5 bg-muted rounded w-4/5" /></div>
          <div className="flex items-center gap-2"><span>•</span><div className="h-2.5 bg-muted rounded w-3/5" /></div>
          <div className="flex items-center gap-2"><span>•</span><div className="h-2.5 bg-muted rounded w-2/3" /></div>
        </div>
      );

    case 'table':
      return (
        <div className="border rounded overflow-hidden text-xs">
          <div className="grid grid-cols-3 bg-muted/50 text-muted-foreground">
            <div className="p-1.5 border-r">컬럼 A</div>
            <div className="p-1.5 border-r">컬럼 B</div>
            <div className="p-1.5">컬럼 C</div>
          </div>
          {[1, 2].map((r) => (
            <div key={r} className="grid grid-cols-3 border-t">
              <div className="p-1.5 border-r"><div className="h-2.5 bg-muted rounded w-3/4" /></div>
              <div className="p-1.5 border-r"><div className="h-2.5 bg-muted rounded w-2/3" /></div>
              <div className="p-1.5"><div className="h-2.5 bg-muted rounded w-1/2" /></div>
            </div>
          ))}
        </div>
      );

    case 'comparison':
      return (
        <div className="flex gap-2">
          <div className="flex-1 rounded border p-2 text-center text-xs">
            <div className="text-muted-foreground mb-1">이전 기간</div>
            <div className="h-8 bg-muted rounded" />
          </div>
          <div className="flex items-center text-muted-foreground text-xs">→</div>
          <div className="flex-1 rounded border p-2 text-center text-xs">
            <div className="text-muted-foreground mb-1">현재 기간</div>
            <div className="h-8 bg-muted rounded" />
          </div>
        </div>
      );

    case 'alert':
      return (
        <div className="rounded border border-amber-500/30 bg-amber-500/5 p-2 text-xs text-muted-foreground flex items-center gap-2">
          <span>⚠️</span>
          <div className="h-2.5 bg-muted rounded w-4/5" />
        </div>
      );

    case 'timeline':
      return (
        <div className="space-y-2 pl-3 border-l-2 border-muted">
          {[1, 2, 3].map((i) => (
            <div key={i} className="flex items-center gap-2 text-xs">
              <div className="w-2 h-2 rounded-full bg-muted-foreground/30 -ml-[17px]" />
              <div className="text-muted-foreground">시점 {i}</div>
              <div className="h-2.5 bg-muted rounded flex-1" />
            </div>
          ))}
        </div>
      );

    case 'chart':
      return (
        <div className="h-20 rounded border flex items-center justify-center text-muted-foreground text-xs">
          📈 차트 분석이 생성됩니다
        </div>
      );

    case 'recommendation':
      return (
        <div className="rounded border p-2 text-xs text-muted-foreground flex items-start gap-2">
          <span>💡</span>
          <div className="space-y-1 flex-1">
            <div className="h-2.5 bg-muted rounded w-full" />
            <div className="h-2.5 bg-muted rounded w-3/4" />
          </div>
        </div>
      );

    default:
      return null;
  }
}

interface SectionPreviewProps {
  sections: TemplateSection[];
}

/** 리포트 구조를 타입별 레이아웃으로 미리보기한다 */
export function SectionPreview({ sections }: SectionPreviewProps) {
  if (sections.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground text-sm">
        <p>섹션이 없습니다.</p>
        <p className="text-xs mt-1">왼쪽 에디터에서 섹션을 추가해보세요.</p>
      </div>
    );
  }

  return (
    <div className="space-y-3 p-2">
      {sections.map((section, index) => renderSectionPreview(section, index))}
      <div className="text-center text-xs text-muted-foreground pt-2">
        {sections.length}개 섹션
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 빌드 + 타입 체크**

```bash
cd apps/firehub-web && pnpm build
```
Expected: 성공

- [ ] **Step 3: 수동 검증**

브라우저에서 `/ai-insights/templates/{id}` → 사이드패널 "미리보기" 탭:
1. text 섹션: 스켈레톤 텍스트 줄 3개 표시
2. cards 섹션: KPI 카드 3개 (--값)
3. table 섹션: 미니 테이블 스켈레톤
4. group: 제목 + children 재귀 렌더링
5. static 섹션: content 변수 치환 후 실제 텍스트
6. instruction 있는 섹션: 하단에 "AI 지시: ..." 이탤릭 표시
7. 빌더에서 섹션 편집 시 미리보기 즉시 반영

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx
git commit -m "feat(web): 비주얼 빌더 구조+가이드 미리보기 — 타입별 레이아웃 렌더링"
```

---

## Task 8: E2E 테스트 (프론트엔드)

**Files:**
- Modify: `apps/firehub-web/e2e/factories/ai-insight.factory.ts`
- Modify: `apps/firehub-web/e2e/fixtures/ai-insight.fixture.ts`
- Modify: `apps/firehub-web/e2e/pages/ai-insights/job-detail.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/ai-insights/template-detail.spec.ts`

- [ ] **Step 1: 팩토리에 anomaly event 추가**

`e2e/factories/ai-insight.factory.ts`에 추가:

```typescript
import type { AnomalyEventRecord } from '../../src/api/proactive';

export function createAnomalyEvent(overrides: Partial<AnomalyEventRecord> = {}): AnomalyEventRecord {
  return {
    id: 1,
    jobId: 1,
    metricId: 'pipeline_failure_rate',
    metricName: '파이프라인 실패율',
    currentValue: 45.5,
    mean: 12.3,
    stddev: 5.2,
    deviation: 6.38,
    sensitivity: 'medium',
    detectedAt: '2026-04-06T10:30:00',
    ...overrides,
  };
}
```

- [ ] **Step 2: fixture에 anomaly events 모킹 추가**

`e2e/fixtures/ai-insight.fixture.ts`의 `setupJobDetailMocks()` (또는 해당 함수)에 추가:

```typescript
await mockApi(
  page,
  'GET',
  '/api/v1/proactive/jobs/1/anomaly-events*',
  [
    createAnomalyEvent(),
    createAnomalyEvent({ id: 2, metricName: '데이터셋 수', currentValue: 150, mean: 100, deviation: 3.2 }),
  ],
);
```

- [ ] **Step 3: job-detail E2E — 이상 탐지 이력 테스트**

`e2e/pages/ai-insights/job-detail.spec.ts`에 추가:

```typescript
test('모니터링 탭에 이상 탐지 이력이 표시된다', async ({ authenticatedPage: page }) => {
  await setupJobDetailMocks(page);
  await page.goto('/ai-insights/jobs/1');

  // 모니터링 탭 클릭
  await page.getByRole('tab', { name: /모니터링/ }).click();

  // 이상 탐지 이력 섹션 헤더 확인
  await expect(page.getByText('최근 이상 탐지')).toBeVisible();

  // 이벤트 데이터가 테이블에 렌더링되는지 확인
  await expect(page.getByText('파이프라인 실패율')).toBeVisible();
  await expect(page.getByText('6.38σ')).toBeVisible();
});
```

- [ ] **Step 4: template-detail E2E — 미리보기 테스트**

`e2e/pages/ai-insights/template-detail.spec.ts`에 추가:

```typescript
test('미리보기 탭에 섹션 타입별 플레이스홀더가 표시된다', async ({ authenticatedPage: page }) => {
  await setupTemplateDetailMocks(page);
  await page.goto('/ai-insights/templates/1');

  // 미리보기 탭 클릭
  await page.getByRole('tab', { name: /미리보기/ }).click();

  // 섹션 수 표시 확인
  await expect(page.getByText(/개 섹션/)).toBeVisible();
});
```

- [ ] **Step 5: E2E 테스트 실행**

```bash
cd apps/firehub-web && pnpm test:e2e --grep "모니터링|미리보기"
```
Expected: 전체 통과

- [ ] **Step 6: 전체 E2E 회귀 확인**

```bash
cd apps/firehub-web && pnpm test:e2e
```
Expected: 177개 전체 통과

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(e2e): 이상 탐지 이력 + 미리보기 E2E 테스트 추가"
```

---

## Task 9: 백엔드 테스트 + 전체 검증

**Files:**
- Create/Modify: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/repository/AnomalyEventRepositoryTest.java`

- [ ] **Step 1: AnomalyEventRepository 통합 테스트**

```java
package com.smartfirehub.proactive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.IntegrationTestBase;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AnomalyEventRepositoryTest extends IntegrationTestBase {

    @Autowired
    private AnomalyEventRepository repository;

    @Test
    void save_and_findByJobId() {
        // Given: 이상 탐지 이벤트 생성
        var event = new AnomalyEvent(
            1L, 1L, "pipeline_failure_rate", "파이프라인 실패율",
            45.5, 12.3, 5.2, 6.38, "medium", List.of(10.0, 12.0, 14.0));

        // When: 저장
        repository.save(event);

        // Then: 조회 시 저장된 이벤트가 반환된다
        var results = repository.findByJobId(1L, 10);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).metricName()).isEqualTo("파이프라인 실패율");
        assertThat(results.get(0).deviation()).isEqualTo(6.38);
    }

    @Test
    void findByJobId_respects_limit() {
        // Given: 이벤트 3개 저장
        for (int i = 0; i < 3; i++) {
            repository.save(new AnomalyEvent(
                1L, 1L, "metric_" + i, "메트릭 " + i,
                i * 10.0, 5.0, 2.0, i * 1.5, "high", List.of()));
        }

        // When: limit 2로 조회
        var results = repository.findByJobId(1L, 2);

        // Then: 최대 2개만 반환된다
        assertThat(results).hasSize(2);
    }
}
```

- [ ] **Step 2: 백엔드 전체 테스트**

```bash
cd apps/firehub-api && ./gradlew test
```
Expected: 전체 통과

- [ ] **Step 3: 프론트엔드 전체 빌드**

```bash
cd apps/firehub-web && pnpm build
```
Expected: 성공

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/test/
git commit -m "test(proactive): AnomalyEventRepository 통합 테스트 추가"
```

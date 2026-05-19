# 대시보드 실행 이력 TTL + Stale 카드 제거 — 설계

- 관련 Issue: #223 (축소판으로 진행), #224 (폐기)
- Date: 2026-05-19
- Status: Draft (approval pending)

## 1. 배경

홈 "주의 필요" 카드는 세 카테고리를 노출한다:
1. 실패한 파이프라인
2. Stale 데이터셋
3. 24h 내 실패 임포트

또한 활동 피드(ActivityFeed)에는 파이프라인 실패 이력이 누적된다. 운영자가 노이즈를 정리하기 위해 운영 DB에 직접 `DELETE`를 수행하는 임시 조치가 발생하고 있다 (이번 세션 21건).

브레인스토밍 결과:

- **Stale 카드**는 약한 시그널이다. "마지막 임포트가 N시간 전"은 임포트 실패(#1 카드와 중복) 또는 정상 운영(일회성·주간 갱신) 또는 스케줄 누락(개념 자체가 시스템에 없음)을 한 줄에 뭉뚱그린 저해상도 지표. 운영 stale 23건 중 15건은 import 이력 자체가 없어 분류가 잘못됨. → **카드 자체 제거**가 ack/dismiss 우회보다 깨끗.
- **acknowledge 컬럼·UI**는 isResolved(다음 성공 실행 시 자동 해제)가 대부분 케이스를 커버하므로 보류. 실제 필요 케이스 발생 시 후속 이슈.
- **TTL 자동 정리**는 운영 위생 작업으로 가치 명확. 손으로 DELETE 치는 운영 작업을 제거.
- **`trigger_event.execution_id` FK CASCADE**는 TTL의 부수 필수 조건.

## 2. 목표

1. `pipeline_execution` 무한 누적을 자동 정리 (90일 TTL).
2. `trigger_event` FK를 CASCADE로 변경하여 TTL이 자식까지 깔끔히 정리.
3. 홈 "주의 필요"에서 stale 데이터셋 카드 제거 — 알림 가치 없는 노이즈 차단.

## 3. 비-목표

- `pipeline_execution.acknowledged_at` / acknowledge API·UI (#223 원안의 일부) — 보류.
- `dataset.expected_refresh_interval_minutes` / `dismissed_attention` (#224) — 폐기.
- audit_log TTL — 별도 정책 필요, 본 범위 외.
- 스케줄 누락(cron 예정 vs 실제 실행) 알림 — 시스템에 스케줄 개념 없음, 별도 설계 필요, 본 범위 외.

## 4. DB 변경 — V59 마이그레이션

현재 최신 마이그레이션은 V58이므로 신규는 V59. 기존 FK 이름은 `trigger_event_execution_id_fkey`로 확인됨.

```sql
-- V59__trigger_event_cascade_on_execution_delete.sql
-- trigger_event.execution_id FK 를 ON DELETE CASCADE 로 변경.
-- pipeline_execution TTL 정리 시 자식 trigger_event 행이 자동 정리되도록.

ALTER TABLE trigger_event
    DROP CONSTRAINT IF EXISTS trigger_event_execution_id_fkey;

ALTER TABLE trigger_event
    ADD CONSTRAINT trigger_event_execution_id_fkey
        FOREIGN KEY (execution_id) REFERENCES pipeline_execution(id) ON DELETE CASCADE;
```

`application.yml`의 `flyway.baseline-version`을 59로 업데이트.

데이터 변경 없음, 짧은 lock, 안전.

## 5. TTL 스케줄러

### 5.1 클래스: `PipelineExecutionTtlJob` (신규)

위치: `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJob.java`

```java
@Component
public class PipelineExecutionTtlJob {

  @Value("${firehub.execution.ttl.days:90}")
  private int retentionDays;

  // 매일 자정 (KST). cron 은 env override 가능.
  @Scheduled(cron = "${firehub.execution.ttl.cron:0 0 0 * * *}")
  public void runScheduled() {
    runOnce();
  }

  /** 단위 테스트에서 호출 가능한 핵심 정리 로직. 삭제 행 수 반환. */
  public int runOnce() {
    int deleted = dsl.deleteFrom(PIPELINE_EXECUTION)
      .where(PIPELINE_EXECUTION.CREATED_AT.lt(LocalDateTime.now().minusDays(retentionDays)))
      .and(PIPELINE_EXECUTION.STATUS.eq("COMPLETED"))
      .execute();
    log.info("PipelineExecutionTtl: deleted {} rows older than {} days", deleted, retentionDays);
    return deleted;
  }
}
```

### 5.2 정책

- **대상**: `pipeline_execution.created_at < NOW() - <days>` **AND** `status = 'COMPLETED'`.
- **실패 행은 보존** — 디버깅·재시도 단서 유지. acknowledge 컬럼 도입 안 했으니 단순.
- **CASCADE**: `trigger_event` 자식 행은 자동 정리.
- **90일은 디폴트**, `firehub.execution.ttl.days` 로 override.
- 첫 실행 시 누적된 다량 행 삭제 가능. dry-run 가드는 YAGNI.

### 5.3 `@EnableScheduling`

기존 Spring Boot 설정에 `@EnableScheduling`이 없다면 `FirehubApiApplication` 또는 별도 `@Configuration` 클래스에 추가. 이미 있으면 skip.

## 6. Stale 카드 제거

### 6.1 `DashboardService.getAttentionItems`

위치: `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java:329`

기존 메서드 안의 **stale 데이터셋 계산 블록(#2)** 제거. 빌드 결과의 응답에서 stale 항목이 빠짐.

### 6.2 응답 DTO

`AttentionItemResponse` 또는 dashboard data response 클래스에서 `staleDatasets` 필드 제거.

(클래스 이름은 구현 시 `DashboardController` 응답 타입에서 정확히 확인.)

### 6.3 기존 단위 테스트

`DashboardServiceTest` 등 stale 관련 테스트 케이스 제거 또는 갱신(stale 항목이 응답에 **없는지** 검증).

## 7. Frontend 변경

### 7.1 타입

`apps/firehub-web/src/types/dashboard.ts` (또는 위치) — `staleDatasets` 필드 제거.

### 7.2 UI

홈 AttentionList 컴포넌트에서 stale 섹션 제거. 컴포넌트 위치는 구현 시 `grep "staleDatasets"`로 확인.

### 7.3 E2E

기존 dashboard 관련 E2E에서 stale 카드 검증을 제거. 응답 모킹도 `staleDatasets` 필드 제외하도록 fixture 갱신. 신규 spec 추가 없음.

## 8. 테스트

### 8.1 `PipelineExecutionTtlJobTest`

시나리오 매트릭스:

| created_at | status | 기대 |
| --- | --- | --- |
| 100일 전 | COMPLETED | 삭제 |
| 100일 전 | FAILED | 보존 |
| 89일 전 | COMPLETED | 보존 (윈도우 내) |
| 100일 전 COMPLETED + trigger_event 자식 1행 | — | CASCADE로 함께 삭제 |

`@SpringBootTest` 또는 jOOQ 통합 테스트 패턴 (현 프로젝트 관행에 맞춤). retentionDays override (생성자 주입 또는 `ReflectionTestUtils`) 검증.

### 8.2 `DashboardServiceTest`

- stale 데이터셋 시드 → `getAttentionItems()` 응답에 stale 없음 단언.
- failed pipeline / recent failed import 시드 → 정상 노출 회귀 단언.

### 8.3 Frontend E2E

기존 dashboard spec 통과 유지. stale 관련 부분만 제거.

## 9. 커밋 전략

**단일 커밋**으로 모두 묶는다 (사용자 지침). 변경 사항을 task 별로 staging하되 commit은 1회.

커밋 메시지(안):
```
feat(dashboard): pipeline_execution 90일 TTL + stale 카드 제거 (refs #223, closes #224)

- V59 마이그레이션: trigger_event.execution_id FK ON DELETE CASCADE
- PipelineExecutionTtlJob: 매일 자정, COMPLETED + 90일 경과 행 삭제 (env override)
- DashboardService.getAttentionItems: stale 데이터셋 블록 제거
- Frontend: AttentionList stale 섹션 + 타입 정리
- 테스트: PipelineExecutionTtlJobTest, DashboardServiceTest 갱신
```

## 10. 이슈 처리 (구현 완료 후)

- **#223**: 코멘트(축소 결정 + 본 스펙·계획서 링크 + 커밋 SHA) 후 close (`completed`).
- **#224**: 코멘트(폐기 결정 + 본 스펙 링크) 후 close (`not planned`).

## 11. 변경 파일 요약

신규:
- `apps/firehub-api/src/main/resources/db/migration/V59__trigger_event_cascade_on_execution_delete.sql`
- `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJob.java`
- `apps/firehub-api/src/test/java/com/smartfirehub/dashboard/job/PipelineExecutionTtlJobTest.java`

수정:
- `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java`
- `apps/firehub-api/src/main/java/.../<AttentionResponse DTO>.java`
- 필요 시 `@EnableScheduling` 추가 위치
- `apps/firehub-api/src/test/java/com/smartfirehub/dashboard/service/DashboardServiceTest.java`
- `apps/firehub-api/src/main/generated/...` (jOOQ 재생성 산출물)
- `apps/firehub-web/src/types/dashboard.ts` (또는 응답 타입 위치)
- `apps/firehub-web/src/components/.../AttentionList.tsx` (또는 stale 섹션 위치)
- `apps/firehub-web/e2e/.../dashboard*.spec.ts` (stale 검증 제거)

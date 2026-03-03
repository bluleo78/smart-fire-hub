# Phase 4: 대시보드 전체 개선 — 리서치 보고서

> **작성일**: 2026-03-03
> **범위**: 홈 대시보드 + 분석 대시보드 + 알림/이벤트 시스템

---

## 1. 현재 상태 분석

### 1.1 홈 대시보드 (`/`)

| 항목 | 현재 상태 | 문제점 |
|------|----------|--------|
| 데이터 | 단일 `GET /dashboard/stats` 호출 | 페이지 로드 시 1회만 fetch, 자동 갱신 없음 |
| 표시 정보 | 데이터셋 수, 파이프라인 수, 최근 임포트 5건, 최근 실행 5건 | **Vanity metrics** — 단순 카운트, 조치 필요 여부 불명 |
| 최근 대시보드 | 최대 4개 카드 | 역할/사용 빈도 고려 없음 |
| 실시간성 | 없음 | 파이프라인 완료/임포트 완료가 반영되지 않음 |

### 1.2 분석 대시보드 (`/analytics/dashboards/:id`)

| 항목 | 현재 상태 | 문제점 |
|------|----------|--------|
| 자동 갱신 | `autoRefreshSeconds` → `setInterval` → `refetch()` | **버그**: 위젯 레이아웃만 refetch, 차트 데이터는 갱신 안됨 |
| 요청 패턴 | 위젯당 2건 (chart metadata + chart data) | N개 위젯 = 2N 요청. 배치 엔드포인트 미사용 |
| 위젯별 갱신 | 불가 | 전체 대시보드 단위 갱신만 가능 |
| 데이터 신선도 | 표시 없음 | 사용자가 데이터가 언제 갱신되었는지 알 수 없음 |
| off-screen | 미처리 | 스크롤 아래 위젯도 동일하게 요청 |

### 1.3 이벤트/알림 인프라

| 항목 | 현재 상태 |
|------|----------|
| `PipelineCompletedEvent` | Spring `ApplicationEvent` → `TriggerEventService`만 소비 (브라우저 미전달) |
| `AsyncJobService` SSE | 임포트 진행률용. JVM-local `ConcurrentHashMap<jobId, List<SseEmitter>>` |
| 데이터셋 변경 감지 | `TriggerEventService.pollDatasetChanges()` — 30초 간격 `pg_stat_user_tables` 폴링 |
| WebSocket | 없음 |
| 알림 시스템 | 없음 |

---

## 2. 홈 대시보드 리서치

### 2.1 데이터 플랫폼 홈페이지 분석

| 플랫폼 | 핵심 패턴 | 우리에게 적용할 점 |
|--------|----------|-------------------|
| **Snowflake Snowsight** | 역할 기반 퀵 액션 + 최근 사용 항목 (탭별) | 퀵 액션 영역, 최근 사용 개인화 |
| **Airbyte** | 연결별 건강 상태 (Healthy/Failed/Running/Paused) | 파이프라인별 상태 표시, 에러 구분 |
| **dbt Cloud** | Data Health Tiles — 사용 지점에 건강 신호 삽입 | 위젯/차트에 신선도 인디케이터 |
| **Dagster** | 에셋 카탈로그 중심 — "무엇이 있고, 건강한가?" | 데이터셋 건강 상태 중심 뷰 |
| **Monte Carlo** | 5대 데이터 건강 지표: Freshness, Volume, Schema, Distribution, Lineage | Freshness + Volume 우선 도입 |
| **Grafana** | General → Specific 레이아웃, RED/USE 프레임워크 | 요약 → 상세 드릴다운 구조 |

### 2.2 Actionable vs. Vanity Metrics

| Vanity (현재) | Actionable (개선) |
|--------------|-------------------|
| 전체 데이터셋: 47개 | 3개 데이터셋이 72시간 이상 미갱신 |
| 전체 파이프라인: 12개 | 2개 파이프라인이 24시간 내 실패 |
| 최근 임포트 (리스트) | 임포트 실패: `sensor_data` — 마지막 성공 48시간 전 |
| 최근 실행 (리스트) | `etl_main` 파이프라인 실행 시간 기준선 대비 3배 느림 |

### 2.3 권장 홈페이지 정보 구조

```
┌─────────────────────────────────────────────────────────────┐
│  ZONE 1: 시스템 건강 상태바 (상단 고정)                         │
│  ● 파이프라인: 2 실패, 1 실행중, 9 정상                         │
│  ● 데이터셋: 3 오래됨 (>24h), 44 정상                          │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  ZONE 2: 주의 필요 (이슈가 있을 때만 표시)                      │
│  에러/경고/오래된 데이터셋 카드. 심각도순 정렬.                    │
│  이슈 없을 때: "모든 시스템 정상" — 영역 축소/숨김               │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  ZONE 3: 퀵 액션                                              │
│  [새 데이터셋]  [파이프라인 실행]  [SQL 편집기]  [새 대시보드]    │
└─────────────────────────────────────────────────────────────┘

┌──────────────────────────┬──────────────────────────────────┐
│  ZONE 4: 최근 사용        │  ZONE 5: 활동 피드               │
│  - 최근 대시보드 5개       │  - 상태 변화만 표시              │
│  - 최근 데이터셋 5개       │  - 미해결 이슈 상단 고정         │
│  - 최근 SQL 쿼리          │  - 유형/상태별 필터              │
│  (개인화된 목록)           │  - 성공만인 항목은 제외          │
└──────────────────────────┴──────────────────────────────────┘
```

---

## 3. 알림 시스템 리서치

### 3.1 알림 피로 방지 원칙

| 원칙 | 설명 |
|------|------|
| **상태 변화만 알림** | healthy→failed 전환 시만 알림. 이미 실패 중인 것은 반복 알림 안함 |
| **엔티티별 통합** | "파이프라인 X에 3건 이슈" > 3개 개별 알림 |
| **시간창 중복 제거** | 10분 내 동일 실패 5건 → 1건 알림 + 카운트 |
| **자동 해소** | 실패 → 복구 시 자동으로 이전 알림 해소 처리 |

### 3.2 3단계 우선순위 모델

| 우선순위 | 예시 | 전달 방식 |
|---------|------|----------|
| **P1 — Critical** | 2시간+ 지속 실패, 데이터 손상 | 인라인 배너 + 벨 뱃지 |
| **P2 — Warning** | 실행 시간 기준선 초과, 신선도 임계치 접근 | 벨 뱃지 + 피드 항목 |
| **P3 — Info** | 파이프라인 성공, 임포트 완료 | **피드에만 기록, 뱃지 없음** |

### 3.3 활동 피드 설계

- **미해결 이슈 상단 고정** (시간순이 아닌 심각도+미해결 우선)
- **해소된 항목은 하위로** 이동
- **필터**: 유형별 (파이프라인/데이터셋/AI), 상태별 (실패/경고/실행중/완료), 시간 범위
- **성공만인 완료 항목**: 피드에는 포함하되 알림 뱃지 미증가

---

## 4. 분석 대시보드 실시간 갱신 리서치

### 4.1 기술 비교

| 기술 | 방향 | 지연 | 복잡도 | 자동 재연결 | 추천 |
|------|------|------|--------|-----------|------|
| **SSE** | Server → Client | 낮음 | 낮음 | 브라우저 내장 | **우리 용도에 적합** |
| WebSocket | 양방향 | 최저 | 중간 | 수동 구현 필요 | 양방향 불필요 |
| Long Polling | Server → Client | 높음 | 중간 | 수동 구현 필요 | 레거시 |

**결정: SSE invalidation + TanStack Query refetch 하이브리드 패턴**

- SSE는 "무엇이 변경되었는지" 알림만 전달
- 실제 데이터는 TanStack Query가 invalidation 후 refetch
- HTTP/1.1 6-connection 제한 → 대시보드당 SSE 1개 연결 공유

### 4.2 위젯별 갱신 패턴

```typescript
// TanStack Query v5 per-widget refetchInterval
function useWidgetData(widgetId: string, refreshSeconds: number) {
  return useQuery({
    queryKey: ['widget', widgetId],
    queryFn: () => fetchWidgetData(widgetId),
    staleTime: Infinity,
    refetchInterval: refreshSeconds * 1000,
    refetchIntervalInBackground: false,  // 탭 숨김 시 중지
    refetchOnWindowFocus: false,
  });
}
```

### 4.3 주요 플랫폼 갱신 전략

| 플랫폼 | 갱신 모델 | 위젯별? | SSE/WS? |
|--------|----------|---------|---------|
| **Grafana** | 대시보드 레벨 interval | 아니오 (장기 요청 중) | 아니오 — 폴링 |
| **Metabase** | 캐시 정책 기반 (Duration/Schedule/Adaptive) | 예 (질문별 정책) | 아니오 |
| **Superset** | 대시보드 레벨 + Redis 캐시 | 차트별 timeout 설정 | 아니오 |
| **Redash** | 쿼리별 스케줄 갱신 | 예 (쿼리별) | 아니오 |

### 4.4 성능 최적화

| 기법 | 설명 |
|------|------|
| **Jitter** | 위젯 갱신 간격에 ±10% 랜덤 오프셋 → thundering herd 방지 |
| **Intersection Observer** | 스크롤 밖 위젯은 fetch 중지 (`enabled: isVisible`) |
| **요청 중복 제거** | TanStack Query 내장 — 같은 queryKey 공유 시 1회만 요청 |
| **배치 엔드포인트** | 기존 `getDashboardData` 활용 → N×2 요청을 1건으로 축소 |
| **Skeleton 로딩** | 초기 로딩은 skeleton, 갱신 중은 subtle overlay |

### 4.5 데이터 신선도 UX

| 상태 | 시각적 처리 |
|------|-----------|
| **Live** | 초록 점 + "방금 갱신" 타임스탬프 |
| **Stale** | 희미한 오버레이 + 주황 뱃지 + "N분 전" |
| **Paused** | 정적 외관 + "일시정지" 뱃지 |

TanStack Query의 `dataUpdatedAt` + `isFetching` 활용으로 추가 상태 관리 불필요.

---

## 5. Spring Boot SSE 구현 방향

### 5.1 아키텍처

```
[Pipeline/Import 완료]
        ↓
  Spring ApplicationEvent
        ↓
  NotificationService (@EventListener)
        ↓
  SseEmitter broadcast (user-scoped)
        ↓
  React EventSource listener
        ↓
  queryClient.invalidateQueries (선택적)
```

### 5.2 기존 인프라 활용

- `AsyncJobService`의 `SseEmitter` 패턴을 확장
- `PipelineCompletedEvent`에 `@EventListener` 추가
- `DataImportService.completeJob()` 시점에 알림 발행

### 5.3 주의사항

- `SseEmitter` (Spring MVC) 사용 — jOOQ blocking JDBC와 호환
- WebFlux `Flux<SSE>` 미사용 (reactive 드라이버 필요)
- emitter timeout 관리 + 재연결 로직 필요

---

## 참고 자료

### 홈 대시보드 / 알림
- [Snowflake Snowsight UI — Snowflake Docs](https://docs.snowflake.com/en/user-guide/ui-snowsight-homepage)
- [Airbyte Connection Status — Airbyte Docs](https://docs.airbyte.com/platform/cloud/managing-airbyte-cloud/review-connection-status)
- [dbt Data Health Tiles — dbt Developer Hub](https://docs.getdbt.com/docs/explore/data-tile)
- [5 Pillars of Data Observability — Monte Carlo](https://www.montecarlodata.com/blog-what-is-data-observability/)
- [Notification Pattern — Carbon Design System](https://carbondesignsystem.com/components/notification/usage/)
- [Notification UX Guidelines — Smashing Magazine 2025](https://www.smashingmagazine.com/2025/07/design-guidelines-better-notifications-ux/)
- [UX Pattern Analysis: Data Dashboards — Pencil & Paper](https://www.pencilandpaper.io/articles/ux-pattern-analysis-data-dashboards)

### 분석 대시보드 / 실시간
- [TanStack Query v5 useQuery Reference](https://tanstack.com/query/v5/docs/framework/react/reference/useQuery)
- [TanStack Query: Query Invalidation](https://tanstack.com/query/v5/docs/framework/react/guides/query-invalidation)
- [SSE vs WebSocket — RxDB](https://rxdb.info/articles/websockets-sse-polling-webrtc-webtransport.html)
- [Spring Boot SSE — Baeldung](https://www.baeldung.com/spring-server-sent-events)
- [TanStack SSE Guide — ollioddi.dev](https://ollioddi.dev/blog/tanstack-sse-guide)
- [Grafana Dashboard Best Practices](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/best-practices/)
- [Metabase Caching Documentation](https://www.metabase.com/docs/latest/configuring-metabase/caching)
- [Kibana Dashboard Rendering Improvements](https://www.elastic.co/search-labs/blog/kibana-dashboard-rendering-time)
- [Thundering Herd + Jitter — PayPal Tech Blog](https://medium.com/paypal-tech/thundering-herd-jitter-63a57b38919d)

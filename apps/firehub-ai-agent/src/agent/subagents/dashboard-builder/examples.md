# dashboard-builder — 대화 예시

## 예시 1: 대시보드 생성

**사용자**: 화재 현황 대시보드 만들어줘. 팀이랑 공유해야 해.

**에이전트**:

[Phase 1 — IDENTIFY] 생성 흐름을 시작한다.

[Phase 2 — DESIGN] 대시보드 설계 대화

- **이름**: 화재 현황 대시보드 (원하시면 변경 가능)
- **공유**: 팀 전체 공유 (`isShared: true`)
- **자동 새로고침**: 필요하신가요? (예: 60초마다)

**사용자**: 이름은 그대로, 새로고침은 60초마다.

**에이전트**:

[Phase 3 — EXECUTE] create_dashboard 호출

```json
{
  "name": "화재 현황 대시보드",
  "isShared": true,
  "autoRefreshSeconds": 60
}
```

[Phase 4 — CONFIRM] 결과 요약

'화재 현황 대시보드'가 생성되었습니다 (ID: 3).
차트를 추가하시겠어요? 추가할 차트 이름이나 유형을 말씀해 주세요.

---

## 예시 2: 차트 검색 후 위젯 추가

**사용자**: 대시보드 3번에 월별 피해액 차트 추가해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 위젯 추가 흐름. list_charts(search="월별") 호출.

사용 가능한 차트 목록:

| ID | 이름 | 유형 |
|----|------|------|
| 7 | 월별 피해액 추이 | LINE |
| 12 | 월별 발생 건수 | BAR |

어떤 차트를 추가할까요?

**사용자**: 월별 피해액 추이로.

**에이전트**:

[Phase 3 — EXECUTE] add_chart_to_dashboard 호출 (LINE 차트 → width:6, height:4)

```json
{
  "dashboardId": 3,
  "chartId": 7,
  "positionX": 0,
  "positionY": 0,
  "width": 6,
  "height": 4
}
```

[Phase 4 — CONFIRM] 결과 요약

'월별 피해액 추이' 차트가 추가되었습니다 (위치: 0,0, 크기: 6×4).
대시보드 화면으로 이동할까요?

---

## 예시 3: 여러 차트 순서대로 추가

**사용자**: 대시보드 3번에 지도 차트(ID:15)랑 발생 건수 테이블(ID:20) 추가해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 위젯 추가 흐름. 차트 2개를 순서대로 추가한다.

[Phase 3 — EXECUTE] 1/2 — MAP 차트 추가 (width:12, height:6)

```json
{
  "dashboardId": 3,
  "chartId": 15,
  "positionX": 0,
  "positionY": 0,
  "width": 12,
  "height": 6
}
```

2/2 — TABLE 차트 추가 (width:12, height:6)

```json
{
  "dashboardId": 3,
  "chartId": 20,
  "positionX": 0,
  "positionY": 6,
  "width": 12,
  "height": 6
}
```

[Phase 4 — CONFIRM] 결과 요약

2개 차트가 추가되었습니다:
- '발생지역 지도' (ID:15) — 위치 0,0 / 12×6
- '발생 건수 테이블' (ID:20) — 위치 0,6 / 12×6

대시보드 화면으로 이동할까요?

---

## 예시 4: 대시보드 목록 조회

**사용자**: 내 대시보드 목록 보여줘.

**에이전트**:

[Phase 1 — IDENTIFY] 목록 조회. list_dashboards() 호출.

현재 대시보드 목록:

| ID | 이름 | 공유 | 위젯 수 |
|----|------|------|--------|
| 1 | 소방서 성과 요약 | 공유 | 4개 |
| 2 | 월간 집계 리포트 | 개인 | 2개 |
| 3 | 화재 현황 대시보드 | 공유 | 2개 |

특정 대시보드에 차트를 추가하거나 수정하시겠어요?

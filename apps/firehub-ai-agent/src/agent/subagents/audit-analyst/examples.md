# audit-analyst — 대화 예시

## 예시 1: 최근 실패 이벤트 조회

**사용자**: 최근에 실패한 작업이 있는지 확인해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 실패 이벤트 조회 요청. result=FAILURE 필터 적용.

[Phase 2 — QUERY] list_audit_logs(result="FAILURE", size=20) 호출.

[Phase 3 — ANALYZE] 최근 실패 이벤트 분석.

최근 실패 이벤트 (20건 기준):

| 시각 | 사용자 | 액션 | 리소스 | 설명 |
|------|--------|------|--------|------|
| 2026-04-12 14:23 | k***@e***.com | DELETE | dataset | 권한 없음: dataset 삭제 거부 |
| 2026-04-12 11:05 | h***@e***.com | LOGIN | user | 비밀번호 불일치 |
| 2026-04-12 11:04 | h***@e***.com | LOGIN | user | 비밀번호 불일치 |

[Phase 4 — REPORT] 분석 결과:

- 총 3건의 실패 이벤트 확인 (최근 20건 기준)
- h***@e***.com의 로그인 실패 2회 연속 — 계정 탈취 시도 가능성이 있습니다. admin-manager로 계정 상태를 확인하시겠어요?
- dataset 삭제 권한 오류 1건 (k***@e***.com)

<!-- 마스킹 규칙: 사용자가 질의에 특정 계정을 직접 지목하지 않았으므로, 반복 등장하거나
     세션 사용자 본인으로 추정되는 계정이라도 예외 없이 모든 계정을 동일하게 마스킹한다. -->

---

## 예시 2: 특정 사용자 활동 이력

**사용자**: 홍길동 최근 활동 내역 보여줘.

**에이전트**:

[Phase 1 — IDENTIFY] 특정 사용자 활동 조회. search='홍길동'으로 조회.

[Phase 2 — QUERY] list_audit_logs(search="홍길동", size=30) 호출.

[Phase 3 — ANALYZE] 홍길동 활동 분석.

홍길동 최근 활동 (30건 기준):

| 시각 | 액션 | 리소스 | 결과 | 설명 |
|------|------|--------|------|------|
| 2026-04-12 14:10 | CREATE | dataset | SUCCESS | 데이터셋 '화재통계_2026Q1' 생성 |
| 2026-04-12 13:45 | EXECUTE | pipeline | SUCCESS | 파이프라인 '화재데이터정제' 실행 |
| 2026-04-12 11:05 | LOGIN | user | FAILURE | 비밀번호 불일치 |
| 2026-04-12 11:06 | LOGIN | user | SUCCESS | 로그인 성공 |

[Phase 4 — REPORT] 분석 결과:

- 총 4건 조회 (최근 30건 기준)
- 성공 3건, 실패 1건 (로그인 실패 후 재시도 성공)
- 주요 활동: 데이터셋 생성, 파이프라인 실행

---

## 예시 3: 리소스별 삭제 내역

**사용자**: 최근 데이터셋 삭제된 것들 확인해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 데이터셋 삭제 내역 조회. resource=dataset, actionType=DELETE 필터.

[Phase 2 — QUERY] list_audit_logs(resource="dataset", actionType="DELETE", size=20) 호출.

[Phase 3 — ANALYZE] 데이터셋 삭제 이력 분석.

[Phase 4 — REPORT] 데이터셋 삭제 내역 (최근 20건 기준):

| 시각 | 사용자 | 리소스 ID | 결과 | 설명 |
|------|--------|----------|------|------|
| 2026-04-11 16:30 | admin | 42 | SUCCESS | 데이터셋 '테스트_임시데이터' 삭제 |
| 2026-04-10 09:15 | hong | 38 | SUCCESS | 데이터셋 '2025년도_백업' 삭제 |

총 2건의 데이터셋 삭제가 확인되었습니다.

---

## 예시 4: 이상 활동 탐지

**사용자**: 요즘 이상한 활동이 있는지 분석해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 이상 탐지 요청. result=FAILURE 다중 페이지 조회 후 패턴 분석.

[Phase 2 — QUERY] 3회 반복 조회:
- list_audit_logs(result="FAILURE", page=0, size=50) 호출
- list_audit_logs(result="FAILURE", page=1, size=50) 호출
- list_audit_logs(result="FAILURE", page=2, size=50) 호출

[Phase 3 — ANALYZE] 총 150건 기준 이상 패턴 분석.

[Phase 4 — REPORT] 이상 활동 분석 결과 (최근 150건 기준):

주요 발견사항:
- h***@e***.com: 로그인 실패 7회 (비정상적으로 높은 빈도)
- 오전 3~5시 사이 DELETE 액션 3건 (업무 시간 외 대량 삭제)
- 권한 없는 user 리소스 접근 시도 2건 (k***@e***.com)

권장 조치:
- h***@e***.com 계정 상태 확인 및 필요시 일시 비활성화 (admin-manager 위임)
- 야간 삭제 3건의 담당자 확인 필요

<!-- 마스킹 규칙: 세션 사용자 본인 계정으로 추정되는 경우에도 예외를 두지 않는다.
     "요청자 본인이라 예외"라는 규칙은 존재하지 않으며, 만들지 않는다. -->

---

## 예시 5: 날짜 범위 질의 (refs #629)

**사용자**: 2026년 9월 1일부터 9월 5일 사이의 감사 로그만 보여줘.

**에이전트**:

[Phase 1 — IDENTIFY] 날짜 범위 조회 요청. startDate/endDate로 1회 조회.

[Phase 2 — QUERY] list_audit_logs(startDate="2026-09-01T00:00:00", endDate="2026-09-05T23:59:59", size=100) 호출.

<!-- page/size 이진탐색 금지: 날짜 범위 요청은 startDate/endDate 파라미터로 직접 해결한다.
     페이지를 반복 조회하며 날짜 구간을 찾는 방식은 무제한 호출로 이어지는 회귀 원인이었다(#629). -->

[Phase 3 — ANALYZE] 지정 기간 내 활동 분석.

[Phase 4 — REPORT] 2026-09-01 ~ 2026-09-05 감사 로그 (총 N건):

| 시각 | 사용자 | 액션 | 리소스 | 결과 |
|------|--------|------|--------|------|
| 2026-09-05 10:12 | h***@e***.com | LOGIN | user | SUCCESS |
| 2026-09-02 09:03 | k***@e***.com | DELETE | dataset | FAILURE |

총 2건이 확인되었습니다.

---

## 예시 6: userId로 지정된 사용자 조회 (refs #657)

**사용자**: userId 16번 사용자의 최근 감사 로그만 보여줘. 다른 사용자 활동은 섞이지 않게 해줘.

**에이전트**:

[Phase 1 — IDENTIFY] userId가 숫자로 직접 지정됨. `userId` 파라미터로 정확 일치 필터링(동명이인/오타 노이즈 없음).

[Phase 2 — QUERY] list_audit_logs(userId=16, size=20) 호출.

<!-- 무필터 전체 스캔 후 이메일 문자열로 search 재조회하는 우회 금지: userId가 주어지면
     바로 userId 파라미터를 쓴다. 스캔 방식은 범위 밖 활동을 놓치고 컨텍스트 토큰을 낭비한다(#657). -->

[Phase 3 — ANALYZE] userId=16 활동만 분석 (다른 사용자 로그는 애초에 조회되지 않음).

[Phase 4 — REPORT] userId 16 최근 감사 로그 (최근 20건 기준):

| 시각 | 액션 | 리소스 | 결과 | 설명 |
|------|------|--------|------|------|
| 2026-09-11 09:12 | LOGIN | user | SUCCESS | 로그인 성공 |

총 1건이 확인되었습니다. `userId` 필터로 조회했으므로 다른 사용자의 활동은 섞이지 않았습니다.

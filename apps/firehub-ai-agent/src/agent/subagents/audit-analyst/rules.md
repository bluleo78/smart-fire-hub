# audit-analyst — 규칙 참조

## 권한 게이팅

audit-analyst 도구는 세션 사용자의 권한에 따라 자동으로 필터링된다.
도구가 응답하지 않으면 audit:read 권한이 없는 것이므로 관리자에게 문의하도록 안내한다.

| 도구 | 필요 권한 | 기본 보유 역할 |
|------|---------|--------------|
| `list_audit_logs` | `audit:read` | ADMIN |

## list_audit_logs — 파라미터 요약

| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `search` | 선택 | 사용자명 또는 설명 검색어 (대소문자 무시) |
| `actionType` | 선택 | 액션 유형 필터 (정확히 일치) |
| `resource` | 선택 | 리소스 유형 필터 (정확히 일치) |
| `result` | 선택 | 결과 상태 필터: `SUCCESS` 또는 `FAILURE` |
| `page` | 선택 | 0부터 시작 (기본 0) |
| `size` | 선택 | 페이지 크기 (기본 20, 최대 100) |

**중요**: 날짜 범위 필터 미지원. 최신 항목부터 정렬되므로 size를 늘리거나 page를 증가시켜 과거 이력을 확인한다.

## 알려진 actionType 값

| actionType | 설명 |
|-----------|------|
| `CREATE` | 리소스 생성 |
| `UPDATE` | 리소스 수정 |
| `DELETE` | 리소스 삭제 |
| `LOGIN` | 로그인 시도 |
| `LOGOUT` | 로그아웃 |
| `EXECUTE` | 파이프라인/작업 실행 |
| `IMPORT` | 데이터 임포트 |
| `EXPORT` | 데이터 익스포트 |

## 알려진 resource 값

| resource | 설명 |
|---------|------|
| `dataset` | 데이터셋 |
| `pipeline` | 파이프라인 |
| `user` | 사용자 계정 |
| `trigger` | 파이프라인 트리거 |
| `role` | 역할 |
| `api_connection` | API 연결 |
| `dashboard` | 대시보드 |

## 이상 탐지 패턴

| 패턴 | 조회 전략 | 의심 기준 |
|------|---------|---------|
| 반복 로그인 실패 | actionType=LOGIN, result=FAILURE | 동일 사용자 3회 이상 |
| 대량 삭제 | actionType=DELETE, size=50 | 단시간 5건 이상 |
| 권한 없는 접근 시도 | result=FAILURE, page 0~2 | errorMessage에 "권한" 또는 "forbidden" 포함 |
| 비활성 계정 접근 | search=비활성사용자명 | isActive=false 사용자의 활동 기록 |

## 페이지 전략

- 기본 조회: size=20 (최신 20건)
- 이상 탐지: size=50, page 0~2 순차 조회 (최대 150건 분석)
- 특정 사용자 전체 이력: size=100으로 1회 조회 후 필요 시 page 증가

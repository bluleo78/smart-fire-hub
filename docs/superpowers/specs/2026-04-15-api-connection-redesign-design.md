# Phase 9 — API 연결 리디자인 설계

**작성일**: 2026-04-15
**상태**: Draft (리뷰 대기)
**선행 Phase**: Phase 5.11 (서브에이전트 고도화), Phase 7 (AI 리포트 고도화)
**범위**: Backend (firehub-api) + Frontend (firehub-web) + AI Agent (firehub-ai-agent)

---

## 1. 배경 및 문제

현재 `ApiConnection` 엔티티는 **인증 정보(API Key / Bearer Token)만** 저장하고, 호출 대상이 되는 **엔드포인트(Base URL)를 저장하지 않는다**. 그 결과:

1. **키 오남용 위험**: 저장된 키가 어떤 서비스용인지 메타데이터가 없어, 파이프라인에서 실수로 다른 도메인에 키가 주입될 수 있다.
2. **재사용성 착각**: 실제로 같은 키를 여러 엔드포인트에 재사용하는 경우는 드물다. 키는 보통 특정 벤더/서비스 단위로 발급된다.
3. **검증 불가**: 연결이 실제로 살아있는지 확인할 방법이 없다(테스트 대상 URL을 모름).
4. **UX 혼란**: "Make.com API"라는 이름을 붙여도 실제 Make.com으로 간다는 보장이 없다.
5. **운영 가시성 부재**: 외부 API 장애를 파이프라인 실행 실패 시점에야 인지한다.

## 2. 목표

- ApiConnection을 **특정 외부 서비스에 귀속된 인증 프로필**로 재정의 (Base URL 필수화).
- 파이프라인 API_CALL 스텝에서 저장된 커넥션 선택 시 **baseUrl + path 결합**으로 호출 대상 고정.
- 연결 상태를 주기적으로 체크해 DB에 저장하고, 장애를 조기 인지할 수 있는 **알림/UI 배지** 제공.
- 관리자가 중앙에서 커넥션을 관리하고, 일반 사용자는 **사용 목적으로만** 선택 가능.

**비목표 (후속 Phase)**:
- API 카탈로그/템플릿 (공공데이터포털 등 사전 등록)
- 팀/그룹 단위 소유 모델
- OAuth2 플로우 고도화

## 3. 소유 및 권한 모델

| 작업 | 권한 |
|------|------|
| 조회(상세/목록) — 관리 화면 | 관리자 (`api_connection:read`) |
| 생성/수정/삭제 | 관리자 (`api_connection:write`) |
| 테스트 호출 / 강제 갱신 | 관리자 (`api_connection:write`) |
| 선택 목록 조회 (파이프라인/AI 참조용) | 로그인 사용자 전체 |
| 사용(파이프라인 스텝에서 참조 실행) | 모든 사용자 |

- `created_by`는 감사/표시 용도. 소유자 기반 권한 분기 없음.
- 사용자 목록 엔드포인트는 **민감 필드(`authConfig`, `healthCheckPath`) 제외**한 slim DTO 반환.

## 4. 데이터 모델 변경

### 4.1 V49 마이그레이션

```sql
-- V49__redesign_api_connection.sql
ALTER TABLE api_connection
  ADD COLUMN base_url VARCHAR(500) NOT NULL,
  ADD COLUMN health_check_path VARCHAR(500),
  ADD COLUMN last_status VARCHAR(16),           -- NULL | UP | DOWN
  ADD COLUMN last_checked_at TIMESTAMP,
  ADD COLUMN last_latency_ms INT,
  ADD COLUMN last_error_message VARCHAR(1000);

-- baseline-version: 49 로 갱신
```

기존 레코드가 없는 상태라 `NOT NULL` 즉시 적용 가능. (확인됨)

### 4.2 apiConfig JSON 스키마 (pipeline_step)

**변경 전**:
```json
{ "url": "https://api.example.com/v1/data", "method": "GET", ... }
```

**변경 후**:
```json
// saved 모드 (apiConnectionId != null)
{ "path": "/v1/data", "method": "GET", ... }

// inline 모드 (apiConnectionId == null)
{ "customUrl": "https://api.example.com/v1/data", "method": "GET", ... }
```

`url` 필드는 완전 제거. 현 운영 데이터가 없으므로 마이그레이션 스크립트 불필요(클린 컷).

## 5. 백엔드 변경 (firehub-api)

### 5.1 DTO

```java
public record CreateApiConnectionRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotBlank String authType,
    Map<String, String> authConfig,
    @NotBlank @Pattern(regexp = "^https?://.+") @Size(max = 500) String baseUrl,
    @Pattern(regexp = "^/.*") @Size(max = 500) String healthCheckPath
) {}

public record UpdateApiConnectionRequest(
    String name, String description, String authType,
    Map<String, String> authConfig,
    String baseUrl, String healthCheckPath
) {}

public record ApiConnectionResponse(
    Long id, String name, String description,
    String authType, Map<String, String> maskedAuthConfig,
    String baseUrl, String healthCheckPath,
    String lastStatus, Instant lastCheckedAt,
    Integer lastLatencyMs, String lastErrorMessage,
    Long createdBy, Instant createdAt, Instant updatedAt
) {}

public record ApiConnectionSelectableResponse(
    Long id, String name, String authType, String baseUrl
) {} // 사용자 목록용 slim DTO

public record TestConnectionResponse(
    boolean ok, Integer status, Long latencyMs, String errorMessage
) {}
```

### 5.2 Service

- `ApiConnectionService`
  - `create`/`update`: baseUrl 정규화(trailing `/` 제거, URI 파싱 검증), SsrfProtectionService로 host 검증
  - `testConnection(id)`: `baseUrl + healthCheckPath` 조합 GET, 5초 타임아웃, 인증 주입, 결과 DB 반영(`last_*` 컬럼) + `TestConnectionResponse` 반환
  - `findSelectable()`: 민감 필드 제외 조회
  - `refreshAll(jobId)`: 모든 활성 커넥션 비동기 체크, SSE로 진행률 보고
- 신규 `ApiConnectionHealthCheckScheduler`
  - `@Scheduled(fixedDelay = 600_000)` (10분 간격)
  - 대상: `healthCheckPath IS NOT NULL`
  - 비동기 병렬 실행(`pipelineExecutor` 재사용, per-connection 5초 타임아웃)
  - 상태 전환 감지 → 알림 디스패치

### 5.3 Controller (`ApiConnectionController`)

```
GET    /api/v1/api-connections                  # 관리자: 전체 조회
GET    /api/v1/api-connections/selectable       # 로그인 사용자: slim 목록
GET    /api/v1/api-connections/{id}             # 관리자: 상세
POST   /api/v1/api-connections                  # 관리자: 생성
PUT    /api/v1/api-connections/{id}             # 관리자: 수정
DELETE /api/v1/api-connections/{id}             # 관리자: 삭제
POST   /api/v1/api-connections/{id}/test        # 관리자: 강제 갱신(단일)
POST   /api/v1/api-connections/refresh-all      # 관리자: 강제 갱신(전체, 비동기 Job)
```

### 5.4 파이프라인 실행기

- `ApiCallExecutor`, `ApiCallPreviewService`:
  - 타겟 URL 계산:
    ```java
    String targetUrl;
    if (apiConnectionId != null) {
        ApiConnection conn = repository.findById(apiConnectionId);
        String path = (String) apiConfig.get("path");
        if (path == null || path.isBlank())
            throw new ValidationException("API_CALL: path required when apiConnectionId is set");
        targetUrl = joinUrl(conn.baseUrl(), path);
    } else {
        targetUrl = (String) apiConfig.get("customUrl");
        if (targetUrl == null) throw new ValidationException("API_CALL: customUrl required");
    }
    ```
  - 기존 `apiConfig.url` 참조 코드 전부 제거.
- `joinUrl`: baseUrl 끝 `/` 정규화 + path 앞 `/` 보장 유틸.

### 5.5 알림

- DOWN 전환 → **대시보드 알림**(기존 notification 시스템) + **AI Chat 프로액티브 메시지**(Phase 6 채널)
- UP 전환(DOWN→UP) → 동일 채널로 복구 알림
- `last_status`가 NULL인 최초 체크는 알림 없음
- `audit_log`에 상태 전환 기록 (`resourceType=api_connection`, `action=status_change`)

## 6. 프론트엔드 변경 (firehub-web)

### 6.1 타입 & API

- `src/types/api-connection.ts`: `baseUrl`, `healthCheckPath`, `lastStatus`, `lastCheckedAt`, `lastLatencyMs`, `lastErrorMessage`, `ApiConnectionSelectable`, `TestConnectionResponse` 추가
- `src/api/api-connections.ts`: `testConnection(id)`, `refreshAll()`, `listSelectable()` 함수
- `src/hooks/queries/useApiConnections.ts`: `useTestApiConnection`, `useRefreshAllApiConnections`, `useApiConnectionsSelectable`

### 6.2 관리자 페이지

**`ApiConnectionListPage`** (`/admin/api-connections`)
- 생성 다이얼로그에 필드 추가: Base URL(필수), 헬스체크 경로(선택)
- 테이블 컬럼: 이름 / 인증유형 / Base URL / **상태 배지**(UP 녹/DOWN 적/UNKNOWN 회색, 툴팁에 마지막 확인 시각) / 생성일 / 액션
- 상단에 "**전체 갱신**" 버튼 → `refreshAll` Job 실행 + SSE 진행률 토스트
- Zod: `baseUrl`은 `z.string().url()`, `healthCheckPath`는 `/`로 시작

**`ApiConnectionDetailPage`**
- 편집 폼에 Base URL, 헬스체크 경로
- "**지금 확인**" 버튼 → `useTestApiConnection` → 결과 카드(status code, latency, errorMessage, 성공/실패 색상)
- 상태 히스토리(최근 `last_*` 값)

### 6.3 파이프라인 스텝 config (`ApiCallStepConfig`)

- **saved 모드** (커넥션 선택됨)
  - 커넥션 `baseUrl`을 읽기전용 prefix 박스로 표시
  - URL 입력란 라벨 "경로(Path)", 플레이스홀더 `/v1/scenarios/123/run`
  - `apiConfig.path`에 저장
- **inline 모드**
  - 기존대로 Full URL 입력 → `apiConfig.customUrl`에 저장
- 모드 전환 시 path ↔ customUrl 값 초기화
- 사용자가 커넥션 드롭다운 열 때 `useApiConnectionsSelectable` 호출(slim 목록)

### 6.4 E2E 테스트

- `e2e/pages/admin/api-connections.spec.ts`: baseUrl/헬스체크 입력, 테스트 버튼 동작, 상태 배지, payload 검증
- `e2e/pages/admin/api-connections-refresh-all.spec.ts` (신규): 전체 갱신 Job 흐름
- `e2e/pages/ai-chat/api-connection-manager.spec.ts`: baseUrl 포함 생성 흐름
- `e2e/pages/pipeline/api-call-step.spec.ts`: saved/inline 모드 전환, path 입력
- `e2e/factories/admin.factory.ts`, `e2e/fixtures/admin.fixture.ts`: 신규 필드 반영

## 7. AI 에이전트 변경 (firehub-ai-agent)

### 7.1 MCP 도구

- `create_api_connection`, `update_api_connection`: `baseUrl`(필수/수정 시 선택), `healthCheckPath`(선택) 추가
- `test_api_connection(id)`: 신규. 백엔드 `/test` 프록시
- `list_api_connections` / `get_api_connection`: `lastStatus`, `lastCheckedAt` 포함
- 파이프라인 도구 (`pipeline-tools.ts`): API_CALL 스텝 스키마 `apiConfig.url` → `path`/`customUrl`

### 7.2 서브에이전트

- `api-connection-manager/rules.md`, `examples.md`:
  - Base URL 필수화, URL 정규화 안내
  - 생성 대화 흐름에 "Base URL을 먼저 물어본다" 추가
  - 헬스체크 경로 안내, `test_api_connection` 활용 예제
- `pipeline-builder/step-types.md`, `rules.md`:
  - `apiConfig.url` → `path`(saved) / `customUrl`(inline) 표기 업데이트

### 7.3 시스템 프롬프트 (`system-prompt.ts`)
- API 연결 섹션에 Base URL 필수 + 헬스체크 명시

### 7.4 단위 테스트
- `api-connection-tools.test.ts`, `api-client.test.ts`, `firehub-mcp-server.test.ts`: 신규 필드/도구 검증
- `subagent-loader.test.ts`: 서브에이전트 description 변경 반영

## 8. 에러 처리 & 보안

| 케이스 | 처리 |
|--------|------|
| Base URL 형식 오류 | 400 "유효한 URL이어야 합니다 (http/https)" |
| saved 모드 + path 누락 | 400 "경로를 입력하세요" |
| inline 모드 + customUrl 누락 | 400 "URL을 입력하세요" |
| Base URL이 사설 IP/예약 IP 호스트 | 400 "허용되지 않는 호스트" (SsrfProtectionService) |
| 테스트 호출 타임아웃/5xx | 200 `{ ok: false, errorMessage }` (UX 친화) |
| 삭제 시 참조 파이프라인 존재 | 409 (기존 동작 유지) |
| 일반 사용자가 `/api-connections` 관리 API 호출 | 403 |

**보안 원칙**
- Base URL도 생성/수정 시 SsrfProtectionService 검증 (host resolve → 사설 IP 차단)
- 테스트/헬스체크 호출도 SsrfProtectionService 경유
- 헬스체크 경로는 `/`로 시작 강제, 공백/제어문자 차단
- `authConfig` AES-256-GCM 암호화 유지, 응답 마스킹 유지
- 사용자 slim DTO는 `authConfig`, `healthCheckPath`, `last*` 필드 미포함

## 9. 구현 순서

```
Layer 1 (병렬):
  9-1 Backend: V49 마이그레이션, DTO/서비스/컨트롤러, test 엔드포인트, selectable 엔드포인트
  9-2 AI Agent: MCP 도구/서브에이전트/시스템 프롬프트 갱신 (9-1 스펙 기반)

Layer 2:
  9-3 Frontend: 타입/훅/List·Detail 페이지/ApiCallStepConfig (9-1 의존)
  9-4 Backend: ApiCallExecutor/PreviewService apiConfig.url 제거 + joinUrl 유틸 (9-1 의존)
  9-5 Backend: 헬스체크 스케줄러 + 상태 전환 감지 + 알림 연동 + refresh-all Job (9-1 의존)

Layer 3:
  9-6 Frontend: 상태 배지, 전체 갱신 버튼, 알림 UI 연동 (9-3, 9-5 의존)
  9-7 E2E: api-connections + pipeline API_CALL + 헬스체크 상태 표시 검증 (9-3~9-6 의존)
```

## 10. 검증 기준

- 단위/통합 테스트 통과 (firehub-api `./gradlew test`, firehub-ai-agent `pnpm test`)
- E2E 테스트 통과 (firehub-web `pnpm test:e2e`), 신규 시나리오 포함
- V49 마이그레이션 성공, `baseline-version` 갱신
- 관리자 UI에서 커넥션 생성 → 테스트 → 파이프라인 스텝에서 선택 → 실행 성공
- 10분 스케줄러 동작 확인 (로그 + `last_checked_at` 업데이트)
- DOWN 전환 시 대시보드 알림 + AI Chat 메시지 수신 (수동 검증 또는 stub 테스트)
- 일반 사용자가 selectable 엔드포인트만 호출 가능, 관리 API는 403

## 11. 리스크 및 완화

| 리스크 | 완화 |
|--------|------|
| 외부 API rate limit / 과금 (헬스체크 호출) | healthCheckPath가 없으면 스케줄 대상 제외. 10분 간격 고정. 추후 Connection별 주기 설정 필요 시 후속 Phase. |
| 상태 플랩(UP↔DOWN 반복) 알림 폭주 | 이번 Phase에선 단순 상태 비교. 필요 시 N회 연속 실패 후 DOWN 간주로 확장(후속 Phase). |
| 헬스체크 중 인증 정보 복호화 비용 | `pipelineExecutor` 병렬 제한으로 부하 제어. 측정 후 필요시 튜닝. |
| 스케줄러가 앱 재시작 시 중복 실행 / 누락 | `@Scheduled(fixedDelay)`는 이전 종료 기준. 단일 인스턴스 전제(현 배포 구조 일치). 멀티 인스턴스 전환 시 분산 락 필요(후속 Phase). |
| AI 에이전트/파이프라인 기존 저장값 호환 | 현 시점 운영 데이터 없음 확인. 클린 컷. |

## 12. 열린 질문 (후속 Phase 후보)

- API 카탈로그/템플릿 (공공데이터포털, Make.com 등)
- Connection별 헬스체크 주기 설정
- OAuth2 리프레시 토큰 자동 갱신
- 멀티 인스턴스 배포 시 스케줄러 분산 락
- 파이프라인 스텝 config JSON 스키마 버전 관리

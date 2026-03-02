# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

See also the root [CLAUDE.md](../../CLAUDE.md) for monorepo-level commands and cross-app architecture.

## Commands

```bash
# Run / Build
./gradlew bootRun --args='--spring.profiles.active=local'   # 로컬 개발 서버 (8080)
./gradlew build                                              # 빌드 (테스트 포함)
./gradlew build -x test                                      # 빌드 (테스트 제외)

# Tests
./gradlew test                                                                     # 전체 테스트
./gradlew test --tests "com.smartfirehub.auth.service.AuthServiceTest"             # 단일 클래스
./gradlew test --tests "*.AuthServiceTest.login_success"                           # 단일 메서드
./gradlew test --tests "com.smartfirehub.pipeline.*"                               # 패키지 와일드카드

# jOOQ (DB 실행 중이어야 함)
./gradlew generateJooqSchemaSource   # public 스키마 → src/main/generated/
```

테스트 DB는 `smartfirehub_test` (로컬 DB와 별도). DB가 실행 중이어야 테스트 가능 (`pnpm db:up` 또는 `docker compose up -d`).

## Architecture

Package base: `com.smartfirehub`. Feature-sliced 도메인 모듈, 각 모듈은 `controller/` → `service/` → `repository/` + `dto/`, `exception/` 구조.

### Domain Modules

| Module | Purpose |
|--------|---------|
| `auth` | JWT 인증 (signup/login/refresh/logout). Access token → body, refresh token → HttpOnly cookie |
| `user` | 사용자 CRUD, 프로필, 역할 할당 |
| `role` | 역할 CRUD, 권한 할당. 시스템 역할(ADMIN, USER)은 수정 불가 |
| `permission` | 권한 목록 조회. 코드 형식: `{resource}:{action}` (예: `dataset:read`) |
| `dataset` | 데이터셋 정의/카테고리/태그/즐겨찾기. 생성 시 `data` 스키마에 실제 테이블 생성 |
| `dataimport` | CSV/XLSX 업로드 → 파싱 → 검증 → `data.{table}` 적재. Jobrunr로 비동기 처리 |
| `pipeline` | SQL/Python/API_CALL 기반 ETL 파이프라인. DAG 의존성, 비동기 실행, 트리거 시스템 |
| `dashboard` | 대시보드 통계 (데이터셋/파이프라인/임포트 요약) |
| `apiconnection` | API 인증정보 저장 (BASIC/BEARER/OAUTH2/CUSTOM). AES-256-GCM 암호화 |
| `audit` | 사용자 행위 감사 로그. JSONB 메타데이터 |
| `ai` | AI 세션 관리, 외부 ai-agent 서비스 SSE 프록시 |
| `job` | 범용 비동기 작업 추적. SseEmitter 기반 실시간 상태 스트리밍 |
| `settings` | 시스템 설정 (AI 설정: model, max_turns, temperature 등) |
| `global` | SecurityConfig, JwtAuthenticationFilter, GlobalExceptionHandler, `@RequirePermission`, 공통 DTO |

### Two-Schema Database Design

- **`public` 스키마**: 메타데이터 (user, role, pipeline, dataset 정의, audit_log 등)
- **`data` 스키마**: 동적 사용자 테이블. 데이터셋 생성 시 `DataTableService`가 DDL 실행

`DataTableService`는 raw SQL로 `data` 스키마를 관리. 테이블/컬럼명은 `[a-z][a-z0-9_]*` 패턴만 허용. 지원 타입: TEXT, VARCHAR(n), BIGINT, NUMERIC(18,6), BOOLEAN, DATE, TIMESTAMP, GEOMETRY(Geometry, 4326). GEOMETRY 컬럼은 자동 GiST 인덱스, GeoJSON 문자열 입출력(PostGIS SQL 함수), 타입 변환 차단.

### Data Access: jOOQ (not JPA)

- Repository 레이어는 `DSLContext`로 type-safe SQL 작성
- jOOQ 코드젠 결과: `src/main/generated/` (public 스키마 전용)
- `data` 스키마의 동적 테이블은 `DataTableService`에서 raw SQL로 관리
- Flyway 마이그레이션: `src/main/resources/db/migration/V{n}__*.sql` (V1~V26)

### Pipeline Execution Engine

**DAG 실행**: `PipelineExecutionService`가 Kahn's algorithm으로 토폴로지 정렬 후 순차 실행.

**Step 타입별 실행기** (`pipeline/service/executor/`):
- `SqlScriptExecutor`: `data` 스키마에서 SQL 실행
- `PythonScriptExecutor`: subprocess로 Python 스크립트 실행
- `ApiCallExecutor`: WebClient로 외부 API 호출 → JSON 파싱 → 데이터셋 적재

**API_CALL 특이사항**:
- SSRF 방어: `SsrfProtectionService`가 사설 IP/예약 IP 차단
- 인증: 인라인(step config) 또는 `ApiConnection` 참조 (암호화 저장)
- 페이지네이션: offset 기반 자동 반복
- 로드 전략: APPEND (기본) 또는 REPLACE (temp table swap으로 원자성 보장)
- `JsonResponseParser`: JSONPath로 응답에서 레코드 배열 추출

### Trigger System

**트리거 타입** (`pipeline/service/`):
| Type | 동작 |
|------|------|
| `SCHEDULE` | Cron식 스케줄링 (Spring TaskScheduler + CronTrigger) |
| `API` | 토큰 기반 (SHA-256 해시 저장). `POST /api/v1/triggers/api/{token}` |
| `WEBHOOK` | UUID + 선택적 HMAC-SHA256 서명. `POST /api/v1/triggers/webhook/{webhookId}` |
| `PIPELINE_CHAIN` | 상위 파이프라인 완료 시 실행 (조건: SUCCESS/FAILURE/ANY) |
| `DATASET_CHANGE` | 30초 간격으로 데이터셋 row count 변경 폴링 |

`TriggerSchedulerService`는 `@PostConstruct`에서 모든 활성 schedule trigger를 재등록. 체인 깊이 제한: MAX_CHAIN_DEPTH=10.

### Authentication & Authorization

- **JWT**: HS256. Access token 30분, Refresh token 7일 (HttpOnly cookie)
- **`@RequirePermission("code")`**: 컨트롤러 메서드에 선언, `PermissionInterceptor`가 검증
- **내부 통신**: `Authorization: Internal <token>` + `X-On-Behalf-Of: userId` 헤더
- **공개 엔드포인트**: `/api/v1/auth/**`, `/api/v1/triggers/api/**`, `/api/v1/triggers/webhook/**`
- **`Authentication.getPrincipal()`**: `Long userId` 반환

### Async & Streaming

- **Jobrunr**: 데이터 임포트 비동기 처리. `@Job` 어노테이션. Dashboard: localhost:8000 (local 프로필)
- **AsyncJobService**: JVM 내 `ConcurrentHashMap<UUID, List<SseEmitter>>` 기반 실시간 추적
- **AI 프록시**: `AiAgentProxyService`가 외부 ai-agent 서비스(localhost:3001)로 SSE 스트리밍 중계
- **비동기 설정**: `AsyncConfig` — pipelineExecutor (core=5, max=10, queue=25)

### Encryption

`EncryptionService`: AES/GCM/NoPadding, 12바이트 IV, 256비트 키. 출력: Base64 `iv:ciphertext`. API connection 인증정보와 webhook secret 암호화에 사용.

## Configuration

| Profile | DB | Jobrunr Dashboard | AI Agent |
|---------|--------|-----------|----------|
| `local` | `smartfirehub` (localhost:5432) | enabled (port 8000) | localhost:3001 |
| `test` | `smartfirehub_test` (localhost:5432) | disabled | localhost:9999 (stub) |

환경변수: `JWT_SECRET` (Base64 인코딩, 256비트 이상), `ENCRYPTION_MASTER_KEY` (Base64 인코딩). local 프로필은 하드코딩된 개발용 키 사용.

## Testing

모든 테스트는 `IntegrationTestBase`를 상속하는 `@SpringBootTest` + `@ActiveProfiles("test")` 통합 테스트.

```java
class MyServiceTest extends IntegrationTestBase {
    @Autowired private MyService myService;
    @MockitoBean private SomeDependency dep;  // 필요시 mock
    // ...
}
```

- Mockito + `spring-security-test` 사용
- WireMock: API_CALL 관련 HTTP mock (`wiremock-standalone:3.10.0`)
- JVM args (`--add-opens`)는 `build.gradle.kts`에 설정됨
- 서비스 레이어 중심으로 정상/예외 케이스 커버

## Local DB Access

로컬 개발 DB는 Docker Compose로 관리된다. 컨테이너 이름과 접속 정보를 정확히 사용할 것.

```bash
# 컨테이너 이름: smart-fire-hub-db-1 (docker-compose.yml의 서비스명 "db")
# DB User: app / Password: app (POSTGRES_USER/POSTGRES_PASSWORD)
# DB Name: smartfirehub

# DB 접속 (psql)
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c "SELECT ..."

# 테이블 조회 (public 스키마)
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';"

# 테이블명이 예약어인 경우 반드시 큰따옴표 사용 (예: "user" 테이블)
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c 'SELECT * FROM "user";'
```

**주의사항:**
- DB 유저는 `app`이다. `postgres`, `smartfirehub` 등 다른 이름으로 접속하면 `role does not exist` 에러 발생
- `user` 테이블은 PostgreSQL 예약어이므로 반드시 `"user"` (큰따옴표)로 감싸야 한다
- `pnpm db:reset`은 모든 데이터를 삭제하므로, 데이터 보존이 필요한 경우 절대 사용하지 않는다

## Flyway Migration Rules

Flyway는 DB 스키마 버전 관리 도구이다. 아래 규칙을 반드시 준수할 것.

### 마이그레이션 파일
- 경로: `src/main/resources/db/migration/V{n}__*.sql`
- 현재 최신: V27 (analytics 테이블)
- 새 마이그레이션 추가 시 번호를 순차 증가 (V28, V29, ...)

### baseline-on-migrate 설정 (중요)
```yaml
# application.yml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true    # 기존 DB에 flyway_schema_history가 없을 때 자동 baseline 생성
    baseline-version: 27         # 현재 최신 마이그레이션 버전과 일치시킬 것
```

**이 설정이 필요한 이유:**
- 로컬 DB에 이미 테이블이 존재하지만 `flyway_schema_history` 테이블이 없는 경우, Flyway가 `Found non-empty schema(s) "public" but no schema history table` 에러를 발생시킨다
- `baseline-on-migrate: true`는 이 상황에서 자동으로 baseline 레코드를 생성하여 정상 기동되도록 한다
- **기존 데이터를 건드리지 않는다** — flyway_schema_history에 마커만 추가할 뿐이다

### 절대 하지 말 것
- `pnpm db:reset`으로 로컬 DB를 날리지 않는다 (사용자 데이터 손실)
- `flyway clean`을 실행하지 않는다 (모든 스키마 삭제)
- `baseline-version`을 실제 최신 마이그레이션보다 낮게 설정하지 않는다 (이미 적용된 마이그레이션을 재실행하려 시도)

### 새 마이그레이션 추가 시 체크리스트
1. `baseline-version`을 새 마이그레이션 버전으로 업데이트 (예: V28 추가 시 `baseline-version: 28`)
2. 마이그레이션 SQL에 `IF NOT EXISTS` 사용 권장 (멱등성 보장)
3. 새 권한 추가 시 seed INSERT 포함

## Key Conventions

- 새 모듈은 기존 모듈 구조를 따름: `controller/` → `service/` → `repository/` + `dto/`, `exception/`
- 동적 테이블 관련 DDL은 반드시 `DataTableService`를 통해 실행 (SQL injection 방지)
- `@RequirePermission`은 메서드/클래스 레벨 모두 지원
- jOOQ 코드젠은 `public` 스키마만 대상. `data` 스키마는 런타임 동적 관리

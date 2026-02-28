# Smart Fire Hub - Code Review TODO

> 리뷰 일자: 2026-02-14
> 리뷰어: Security / Quality / API / Style Reviewer Agents

## P0 - 즉시 (24시간 이내)

- [x] **[Security/CRITICAL]** JWT Secret Key Git 노출 제거 및 키 재발급
  - `application-local.yml` — 환경변수 참조 + 개발용 기본값으로 변경 완료
  - Git history 클린업(BFG)은 별도 작업

## P1 - 1주 이내

- [x] **[Security/HIGH]** CORS 설정 추가
  - `SecurityConfig.java`에 `CorsConfigurationSource` 빈 + `CorsProperties` 추가 완료
  - 환경변수 `CORS_ALLOWED_ORIGINS`로 프로덕션 도메인 설정

- [x] **[Security/HIGH]** 로그인 실패 제한 구현 (Brute Force 방어)
  - `LoginAttemptService` 생성 완료 (Caffeine 캐시, 5회 실패 시 15분 차단)
  - `AccountLockedException` → HTTP 429 응답

- [ ] **[Security/HIGH]** Refresh Token Rotation 구현
  - `AuthService.java:104-126`
  - Token family 관리 및 재사용 감지 시 전체 세션 폐기
  - `RefreshTokenRepository`에 `isTokenReused()`, `revokeTokenFamily()` 추가

- [x] **[Quality/HIGH]** 토큰 갱신 시 사용자 활성 상태 확인
  - `AuthService.refresh()`에 `isActive` 체크 추가 완료
  - 비활성 사용자는 `UserDeactivatedException` throw

## P2 - 2주 이내

- [x] **[Quality/CRITICAL]** 회원가입 Race Condition 수정
  - PostgreSQL advisory lock (`pg_advisory_xact_lock`)으로 첫 사용자 판단 직렬화 완료

- [x] **[Quality/CRITICAL]** N+1 쿼리 성능 개선
  - `UserRepository.setRoles()` — jOOQ multi-row INSERT로 변경 완료
  - `RoleRepository.setPermissions()` — jOOQ multi-row INSERT로 변경 완료

- [x] **[Security/MEDIUM]** LIKE 패턴 이스케이프 처리
  - `LikePatternUtils` 유틸리티 생성, 7개 레포지토리에 적용 완료
  - `%`, `_`, `\` 특수문자 이스케이프 + jOOQ escape char 파라미터 사용

- [x] **[API/MEDIUM]** GlobalExceptionHandler에 일반 예외 핸들러 추가
  - `Exception.class` catch-all 핸들러 추가 완료
  - SLF4J Logger 추가, 고정 메시지로 내부 정보 미노출

- [ ] **[API/MEDIUM]** ErrorResponse 강화
  - `ErrorResponse.java` - `timestamp`, `path` 필드 추가

- [ ] **[API/HIGH]** RefreshTokenRequest DTO 정리
  - Cookie 전용이면 DTO 제거, Body 기반도 지원하려면 양방향 구현

- [ ] **[API/MEDIUM]** LoginRequest 필드명 명확화
  - `LoginRequest.java:7` - `username`에 `@Email` 적용됨
  - `@Email` 제거하거나 필드명을 `email`로 변경

- [ ] **[Quality/MEDIUM]** AuthContext 무한 재렌더링 방지
  - `AuthContext.tsx:35-55` - cleanup 로직 추가, 의존성 배열 수정

- [x] **[Quality/MEDIUM]** 시스템 역할 권한 변경 보호
  - `RoleService.setRolePermissions()`에 `isSystem()` 가드 추가 완료

- [ ] **[Quality/HIGH]** failedQueue 메모리 누수 방지
  - `client.ts:28-43` - 큐 크기 제한, 타임아웃 추가

## P3 - 1개월 이내

- [ ] **[Security/MEDIUM]** SameSite 정책 조정
  - `AuthController.java:76-84` - `Strict` -> `Lax` 변경 검토

- [ ] **[Security/LOW]** JWT에 권한 정보 포함 (성능 최적화)
  - `JwtTokenProvider.java:30-40` - 매 요청 DB 조회 제거
  - 권한 변경 시 기존 토큰 폐기 로직 필요

- [ ] **[API/MEDIUM]** 날짜/시간 포맷 명시
  - `UserResponse.java` - `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")` 추가

- [ ] **[API/LOW]** Pagination 정렬 기능 추가
  - `UserController.java:49-54` - `sort` 파라미터 추가 또는 `Pageable` 사용

- [ ] **[API/LOW]** TokenResponse DTO 분리
  - 로그인 응답과 토큰 갱신 응답에 별도 DTO 사용
  - `@JsonInclude(NON_NULL)` 적용

- [ ] **[Quality/MEDIUM]** 역할 미존재 시 조용한 실패 수정
  - `AuthService.java:68-74` - `ifPresent` -> `orElseThrow` 변경

- [ ] **[Quality/LOW]** Repository 중복 코드 제거
  - `UserRepository.java` - `UPDATED_AT` 설정 패턴 추출

- [ ] **[Quality/LOW]** UserListPage 에러 처리 추가
  - `UserListPage.tsx:27-37` - 에러 상태 표시, `pageSize` 의존성 추가

- [ ] **[Style/MAJOR]** Frontend Import 정렬 규칙 추가
  - ESLint `import/order` 규칙 설정
  - `pnpm lint --fix` 실행

- [ ] **[Style/MINOR]** API 함수 반환 타입 통일
  - `auth.ts:8` - `client.post<void>('/auth/logout')` 등 제네릭 타입 명시

- [ ] **[Security/INFO]** 비밀번호 복잡도 정책 강화
  - 현재 min 8자만 요구
  - 대소문자, 숫자, 특수문자 포함 검증 추가

- [x] **[Security]** 보안 헤더 추가
  - `SecurityConfig.java`에 X-Content-Type-Options, X-Frame-Options, HSTS 설정 완료

- [ ] **[Quality]** 만료된 Refresh Token 정리 배치 작업 구현

---

## 긍정적 평가 (유지할 것)

- jOOQ Type-safe SQL 사용 (SQL injection 기본 방어)
- JWT + HttpOnly Cookie 분리 (Access Token 메모리, Refresh Token 쿠키)
- Java Record DTO (불변성, 간결성)
- Controller -> Service -> Repository 계층 분리 일관성
- Axios interceptor 기반 자동 토큰 갱신
- `@RequirePermission` + `PermissionInterceptor` RBAC 체계
- Zod 기반 프론트엔드 폼 검증
- BCrypt 비밀번호 해싱
- Refresh Token SHA-256 해싱 저장

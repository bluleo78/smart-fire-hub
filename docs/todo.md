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

- [x] **[Security/HIGH]** Refresh Token Rotation 구현
  - `V28__add_refresh_token_family.sql` — `family_id UUID` 컬럼 추가
  - `RefreshTokenRepository`에 `isTokenRevoked()`, `findFamilyIdByTokenHash()`, `revokeByFamilyId()` 추가
  - `AuthService.refresh()`에서 재사용 감지 시 전체 패밀리 폐기
  - `AuthServiceTest`에 로테이션 + 재사용 감지 TC 추가

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

- [x] **[API/MEDIUM]** ErrorResponse 강화
  - `ErrorResponse.java`에 `timestamp`, `path` 필드 추가 완료
  - `GlobalExceptionHandler`에 `buildError()` 헬퍼 메서드로 핸들러 간소화

- [x] **[API/HIGH]** RefreshTokenRequest DTO 정리
  - Cookie 전용 확인 → 사용하지 않는 DTO 삭제 완료

- [x] **[API/MEDIUM]** LoginRequest 필드명 명확화
  - `LoginRequest.java`에서 `@Email` 제거 완료 (login은 가입된 username 수용)

- [x] **[Quality/MEDIUM]** AuthContext 무한 재렌더링 방지
  - 이미 `ignore` 플래그 cleanup, `useCallback`, `useMemo`, `deduplicatedRefresh` 적용됨

- [x] **[Quality/MEDIUM]** 시스템 역할 권한 변경 보호
  - `RoleService.setRolePermissions()`에 `isSystem()` 가드 추가 완료

- [x] **[Quality/HIGH]** failedQueue 메모리 누수 방지
  - `client.ts` — `MAX_QUEUE_SIZE = 100` 큐 크기 제한 추가 완료

## P3 - 1개월 이내

- [x] **[Security/MEDIUM]** SameSite 정책 조정
  - `AuthController.java` — `Strict` → `Lax` 변경 완료 (CSRF 방어 유지 + UX 개선)

- [ ] **[Security/LOW]** JWT에 권한 정보 포함 (성능 최적화)
  - `JwtTokenProvider.java:30-40` - 매 요청 DB 조회 제거
  - 권한 변경 시 기존 토큰 폐기 로직 필요
  - → Phase 7 로드맵에서 처리 예정

- [x] **[API/MEDIUM]** 날짜/시간 포맷 명시
  - `UserResponse.java` — `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")` 추가 완료

- [x] **[Quality/MEDIUM]** 역할 미존재 시 조용한 실패 수정
  - `AuthService.java` — `ifPresent` → `orElseThrow` 변경 완료

- [x] **[Quality/LOW]** UserListPage 에러 처리 추가
  - 이미 `isError` 핸들링 + 에러 메시지 표시 구현됨

- [x] **[Style/MAJOR]** Frontend Import 정렬 규칙 추가
  - `eslint-plugin-simple-import-sort` 설치 + ESLint 규칙 추가 완료
  - `pnpm lint --fix` 실행으로 기존 파일 일괄 정리 완료

- [x] **[Style/MINOR]** API 함수 반환 타입 통일
  - `auth.ts` — `client.post<void>('/auth/logout')` 제네릭 타입 명시 완료

- [x] **[Security/INFO]** 비밀번호 복잡도 정책 강화
  - `SignupRequest`, `ChangePasswordRequest`에 `@Pattern` 추가 완료
  - 최소 1 대문자, 1 소문자, 1 숫자 요구

- [x] **[Security]** 보안 헤더 추가
  - `SecurityConfig.java`에 X-Content-Type-Options, X-Frame-Options, HSTS 설정 완료

- [x] **[Quality]** 만료된 Refresh Token 정리 배치 작업 구현
  - `RefreshTokenCleanupService` — `@Scheduled(cron = "0 0 4 * * *")` 매일 4시 실행
  - 만료 토큰 + 7일 이상 된 revoked 토큰 삭제

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

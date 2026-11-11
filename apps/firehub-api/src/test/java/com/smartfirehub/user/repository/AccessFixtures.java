package com.smartfirehub.user.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * 사용자·역할·권한·테넌트·멤버십·설정 계약 테스트의 DB 픽스처 seam(WD-706). 같은 시나리오를 PG(jOOQ 평문 SQL)와 Tibero(소유자 계정 JDBC)에서
 * 돌리려고 픽스처만 갈아 끼운다.
 *
 * <p>모든 구현은 만든 행을 기억했다가 {@link #cleanup()} 에서 지운다. 리포지토리 호출은 {@link #inTenant}·{@link #noTenant} 로
 * 감싼다 — PG 는 테넌트 트랜잭션, Tibero 는 {@code TenantContext}(리포지토리 프록시가 호출마다 트랜잭션을 연다).
 */
public interface AccessFixtures {

  /** 보안 등급 기본값이 있는 테스트 테넌트 하나. */
  long createTenant();

  /** 전역 사용자(이메일이 null 일 수 있다). */
  long createUser(String username, String email);

  /** 리포지토리로 만든 사용자도 정리 대상에 올린다. */
  void trackUser(long userId);

  /** 전역 사용자의 계정 활성 플래그를 직접 바꾼다. */
  void setUserActive(long userId, boolean active);

  /** 멤버십(status 는 ACTIVE·SUSPENDED). */
  void createMembership(long userId, long tenantId, String status);

  /** 멤버십 라벨을 바꾼다(OWNER·ADMIN·MEMBER) — 프로비저닝의 OWNER→ADMIN 배정 재현용. */
  void setMembershipRole(long userId, long tenantId, String role);

  /** 그 테넌트의 역할 하나(설명은 null 이 아니어야 한다). */
  long createRole(long tenantId, String name, String description);

  /** 사용자에게 역할을 배정한다(그 역할의 테넌트로). */
  void grantRole(long tenantId, long userId, long roleId);

  /** 역할에 권한을 붙인다. */
  void grantPermission(long tenantId, long roleId, long permissionId);

  /** 전역 권한 카탈로그 행 하나. */
  long createPermission(String code, String category, String description);

  /** 역할 행의 수정 시각을 비운다(수정 시각이 비어 있는 옛 행 재현). */
  void clearRoleUpdatedAt(long tenantId, long roleId);

  /** 역할에 붙은 권한 id(오름차순) — 소유자 시점의 실제 행. */
  List<Long> permissionIdsOfRole(long tenantId, long roleId);

  /** 테넌트 상태를 바꾼다(ACTIVE·SUSPENDED). */
  void setTenantStatus(long tenantId, String status);

  /** 테넌트 slug(소유자 시점). */
  String tenantSlug(long tenantId);

  /** 테넌트 이름(소유자 시점). */
  String tenantName(long tenantId);

  /** 테넌트 기본값 시드 루틴을 픽스처로 부른다(PG 함수, Tibero FH_TENANT 프로시저) — 시스템 역할이 필요한 시나리오용. */
  void provisionDefaults(long tenantId);

  /** 그 테넌트의 역할 id(이름으로). 없으면 예외. */
  long roleIdByName(long tenantId, String name);

  /** 그 테넌트의 시스템 역할 이름(오름차순). */
  List<String> systemRoleNames(long tenantId);

  /** 그 테넌트에서 사용자에게 붙은 역할 수. */
  int countUserRoles(long tenantId, long userId);

  /**
   * 그 테넌트의 행 수. table 은 시험 코드의 고정 이름만 넘긴다(role·role_permission·security_level·dataset_category·
   * report_template·tenant_settings).
   */
  int countTenantRows(String table, long tenantId);

  /** 전역 system_settings 행 하나(정리 대상에 올린다). */
  void insertSystemSetting(String key, String value, String description, LocalDateTime updatedAt);

  /** tenant_settings 행의 수정자(행이 없으면 예외). */
  Long tenantSettingUpdatedBy(long tenantId, String key);

  /** 리포지토리로 만든 테넌트도 정리 대상에 올린다. */
  void trackTenant(long tenantId);

  /** 테넌트 생성 시각을 직접 바꾼다(시각 왕복·정렬 재현용, 저장 TZ 벽시계). */
  void setTenantCreatedAt(long tenantId, LocalDateTime createdAt);

  /** 사용자 표시 이름을 바꾼다(검색 정렬 재현용). */
  void setUserName(long userId, String name);

  /** 사용자 생성 시각을 직접 바꾼다(최신순 정렬·시각 왕복 재현용, 저장 TZ 벽시계). */
  void setUserCreatedAt(long userId, LocalDateTime createdAt);

  /** 플랫폼(운영자) 역할 하나 — 이름은 전역 유일이라 호출자가 접두를 붙인다. 정리 대상에 올린다. */
  long createPlatformRole(String name);

  /** 플랫폼 역할에 권한을 붙인다. */
  void grantPlatformPermission(long platformRoleId, long permissionId);

  /** 사용자에게 플랫폼 역할을 배정한다. */
  void grantPlatformRole(long userId, long platformRoleId);

  /** 그 테넌트의 보안 등급 하나(허용 목록 불필요) — 가시성 시험용. 테넌트 정리 때 함께 지운다. */
  long createSecurityLevel(long tenantId, int rank, String name);

  /**
   * 그 테넌트의 데이터셋 카탈로그 행 하나(감사 로그 가시성 시험용, 실제 데이터 테이블은 만들지 않는다). securityLevelId 가 null 이면 테넌트 기본 등급이
   * 채워진다. 테넌트 정리 때 함께 지운다.
   */
  long createDataset(long tenantId, long createdBy, String tableName, Long securityLevelId);

  /**
   * 감사 로그 한 행을 시각을 정해 직접 넣는다(정렬·기간·가시성 시험용). tenantId 가 null 이면 테넌트 없는(플랫폼) 행. 정리 대상에 올린다.
   *
   * @param actionTime 저장 TZ 벽시계 action_time
   * @param metadataJson null 이면 NULL
   */
  long insertAuditLog(
      Long tenantId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      LocalDateTime actionTime,
      String metadataJson);

  /** 리포지토리로 만든 감사 로그도 정리 대상에 올린다(테넌트 없는 행은 테넌트 정리로 지워지지 않는다). */
  void trackAuditLog(long id);

  /** 그 id 의 감사 로그가 지정 tenant_id(null 이면 NULL)로 저장돼 있으면 1, 아니면 0 — 정책을 우회한 실제 행 기준. */
  int countAuditLog(Long tenantId, long id);

  /** 비동기 작업 행의 단계·생성·갱신 시각을 직접 바꾼다(정체·삭제 기준 시험용, 저장 TZ 벽시계). */
  void setAsyncJobState(
      long tenantId, String id, String stage, LocalDateTime createdAt, LocalDateTime updatedAt);

  /** 그 id 의 비동기 작업이 지정 테넌트로 저장돼 있으면 1, 아니면 0 — 정책을 우회한 실제 행 기준. */
  int countAsyncJob(long tenantId, String id);

  /** 그 id 의 비동기 작업 행 수 — 테넌트와 무관하게 정책을 우회해 센다(거절된 쓰기가 아무 행도 남기지 않았는지 확인용). */
  int countAsyncJobAnyTenant(String id);

  /** 지정 테넌트(null 이면 테넌트 없음) 컨텍스트로 리포지토리 호출을 실행한다. */
  <T> T inTenant(Long tenantId, Supplier<T> call);

  /** 테넌트 컨텍스트를 비우고 트랜잭션 없이 실행한다 — 로그인 경로 재현. */
  <T> T noTenant(Supplier<T> call);

  /** 이 DB 가 빈 문자열을 NULL 로 저장하는가(Tibero true, PG false). */
  boolean emptyStringIsNull();

  /** 만든 행을 모두 지운다. */
  void cleanup();
}

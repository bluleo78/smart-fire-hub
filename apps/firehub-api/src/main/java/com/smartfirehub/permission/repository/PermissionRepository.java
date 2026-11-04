package com.smartfirehub.permission.repository;

import com.smartfirehub.permission.dto.PermissionResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 권한(permission 테이블) 조회 리포지토리. 역할-권한(role_permission)·사용자-역할(user_role)·멤버십을 거쳐 권한 코드를 계산한다.
 *
 * <p>구현은 {@code JooqPermissionRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface PermissionRepository {

  List<PermissionResponse> findAll();

  List<PermissionResponse> findByCategory(String category);

  Optional<PermissionResponse> findById(Long id);

  Optional<PermissionResponse> findByCode(String code);

  List<PermissionResponse> findByRoleId(Long roleId);

  /**
   * 주어진 권한 id 들의 코드. 없는 id 는 결과에서 빠진다 — 역할 권한 교체 전에 "새로 추가되는 권한" 을 코드로 판정하기 위해 쓴다(WD-116).
   * permission 은 전역 카탈로그(RLS 없음)다.
   */
  Set<String> findCodesByIds(Collection<Long> ids);

  /**
   * 주어진 권한 id 중 category 가 {@code category} 인 것이 하나라도 있는가 — 역할 권한 저장에서 플랫폼 권한을 거르기 위해 쓴다(WD-280).
   */
  boolean existsInCategory(Collection<Long> ids, String category);

  /**
   * 여러 역할의 권한 코드를 한 번의 IN 질의로 모은다(역할 id → 코드 집합). 역할 부여 범위 검사가 바뀌는 역할마다 따로 조회하지 않게 하기 위해
   * 쓴다(WD-116).
   *
   * <p>role_permission 은 RLS 대상이라 현재 테넌트 역할만 보인다. 권한이 0개이거나 보이지 않는(없는·다른 테넌트) 역할은 결과에 키가 없다 — 호출자가
   * 빈 집합과 "역할 없음" 을 이것만으로 구분해서는 안 된다.
   */
  Map<Long, Set<String>> findCodesByRoleIds(Collection<Long> roleIds);

  /**
   * 사용자의 권한 코드(현재 테넌트, RLS).
   *
   * <p>ACTIVE 멤버십 조인(WD-2): 필터는 요청마다 멤버십을 다시 보지 않으므로, 이 조인이 없으면 정지된 멤버가 access token 만료(최대 30분)까지
   * API 를 계속 쓴다. user_role.tenant_id 와 같은 테넌트의 멤버십이 ACTIVE 일 때만 그 역할의 권한을 인정한다 — 정지 즉시 그 테넌트 권한만 0
   * 이 된다.
   *
   * <p>user 활성 조인(#784): 운영자가 전역 계정을 비활성화하면 이미 발급된 access token 으로도 권한 필요 API 가 즉시 403 이 된다(필터가
   * 요청마다 이 메서드를 부른다). 같은 이유로 비활성 사용자 이름의 비동기 파이프라인 권한 검사 ({@code PermissionChecker})도 거부된다 — 정지
   * 멤버(ACTIVE 조인)와 같은 의미다.
   */
  Set<String> findPermissionCodesByUserId(Long userId);

  /**
   * 이 테넌트에서 권한 {@code code} 를 가진 사용자 id 집합(WD-187). 알림 수신자를 고를 때 접속자마다 권한을 따로 조회하지 않고 한 번의 질의로 받을 수
   * 있는 사람 전체를 만든다.
   *
   * <p>판정 기준은 {@link #findPermissionCodesByUserId} 와 같은 조인({@code #activeMemberGrants})이다 — 계정 활성 +
   * 이 테넌트 ACTIVE 멤버의 역할만 센다. 그래서 정지(SUSPENDED)·제거(멤버십 없음)·계정 비활성 사용자는 저절로 빠진다. user_role 은 RLS 대상이라
   * 호출자가 테넌트 GUC 가 주입된 트랜잭션 안에서 불러야 하고, RLS 와 별개로 tenant_id 를 명시해 다른 테넌트 역할이 섞이지 않게 한다.
   */
  Set<Long> findActiveMemberIdsWithPermission(long tenantId, String code);
}

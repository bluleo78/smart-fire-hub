package com.smartfirehub.role.repository;

import com.smartfirehub.role.dto.RoleResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 역할(role 테이블)과 역할-권한(role_permission)·사용자-역할(user_role) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqRoleRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface RoleRepository {

  List<RoleResponse> findAll();

  Optional<RoleResponse> findById(Long id);

  Optional<RoleResponse> findByName(String name);

  boolean existsByName(String name);

  /**
   * 주어진 id 중 <b>현재 테넌트</b>에 실제로 있는 역할 id 만 돌려준다.
   *
   * <p>role 은 RLS 대상이라 다른 테넌트 역할 id 는 조회되지 않는다. 멤버 추가 요청의 roleIds 검증 (보이지 않는 id 가 하나라도 있으면 400
   * INVALID_ROLE)에 쓴다.
   */
  Set<Long> findExistingIds(Collection<Long> ids);

  RoleResponse save(String name, String description);

  void update(Long id, String name, String description);

  /**
   * 역할 행을 FOR UPDATE 로 잠근다(WD-117). 같은 역할의 권한을 동시에 교체하면 두 트랜잭션이 같은 "변경 전" 권한을 읽어 감사의 추가·제거가 틀어지므로,
   * 권한 교체 전에 역할 단위로 직렬화한다.
   */
  void lockById(Long id);

  /** 이 역할을 가진 사용자 id(현재 테넌트, RLS, 오름차순) — 역할 삭제 감사에 권한을 잃는 사용자를 남기기 위해 쓴다(WD-117). */
  List<Long> findUserIdsByRoleId(Long roleId);

  void deleteById(Long id);

  void addPermission(Long roleId, Long permissionId);

  void removePermission(Long roleId, Long permissionId);

  void setPermissions(Long roleId, List<Long> permissionIds);

  List<RoleResponse> findByUserId(Long userId);

  /**
   * 여러 사용자의 역할을 한 번의 쿼리로 조회한다 (#586).
   *
   * <p>목록 화면에서 사용자마다 {@link #findByUserId}를 호출하면 N+1 쿼리가 발생한다. 이 메서드는 {@code user_role.user_id} 를
   * 함께 조회해 사용자 ID → 역할 목록 맵으로 묶어 반환함으로써, 사용자 목록 조회 시 단일 배치 쿼리로 역할까지 채울 수 있게 한다.
   *
   * @param userIds 역할을 조회할 사용자 ID 목록
   * @return 사용자 ID → 역할 목록(ROLE.ID asc 정렬) 맵. 역할이 없는 사용자는 키 자체가 없다.
   */
  Map<Long, List<RoleResponse>> findByUserIds(List<Long> userIds);
}

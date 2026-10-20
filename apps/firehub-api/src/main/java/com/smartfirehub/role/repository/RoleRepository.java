package com.smartfirehub.role.repository;

import static com.smartfirehub.jooq.Tables.*;

import com.smartfirehub.role.dto.RoleResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RoleRepository {

  private final DSLContext dsl;

  private RoleResponse mapToRoleResponse(Record r) {
    return new RoleResponse(
        r.get(ROLE.ID), r.get(ROLE.NAME), r.get(ROLE.DESCRIPTION), r.get(ROLE.IS_SYSTEM));
  }

  public List<RoleResponse> findAll() {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .orderBy(ROLE.ID.asc())
        .fetch(this::mapToRoleResponse);
  }

  public Optional<RoleResponse> findById(Long id) {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .where(ROLE.ID.eq(id))
        .fetchOptional(this::mapToRoleResponse);
  }

  public Optional<RoleResponse> findByName(String name) {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .where(ROLE.NAME.eq(name))
        .fetchOptional(this::mapToRoleResponse);
  }

  public boolean existsByName(String name) {
    return dsl.fetchExists(dsl.selectOne().from(ROLE).where(ROLE.NAME.eq(name)));
  }

  /**
   * 주어진 id 중 <b>현재 테넌트</b>에 실제로 있는 역할 id 만 돌려준다.
   *
   * <p>role 은 RLS 대상이라 다른 테넌트 역할 id 는 조회되지 않는다. 멤버 추가 요청의 roleIds 검증 (보이지 않는 id 가 하나라도 있으면 400
   * INVALID_ROLE)에 쓴다.
   */
  public Set<Long> findExistingIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(dsl.select(ROLE.ID).from(ROLE).where(ROLE.ID.in(ids)).fetch(ROLE.ID));
  }

  public RoleResponse save(String name, String description) {
    return dsl.insertInto(ROLE)
        .set(ROLE.NAME, name)
        .set(ROLE.DESCRIPTION, description)
        .returning(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .fetchOne(this::mapToRoleResponse);
  }

  public void update(Long id, String name, String description) {
    dsl.update(ROLE)
        .set(ROLE.NAME, name)
        .set(ROLE.DESCRIPTION, description)
        .set(ROLE.UPDATED_AT, LocalDateTime.now())
        .where(ROLE.ID.eq(id))
        .execute();
  }

  /**
   * 역할 행을 FOR UPDATE 로 잠근다(WD-117). 같은 역할의 권한을 동시에 교체하면 두 트랜잭션이 같은 "변경 전" 권한을 읽어 감사의 추가·제거가 틀어지므로,
   * 권한 교체 전에 역할 단위로 직렬화한다.
   */
  public void lockById(Long id) {
    dsl.selectOne().from(ROLE).where(ROLE.ID.eq(id)).forUpdate().fetch();
  }

  /** 이 역할을 가진 사용자 id(현재 테넌트, RLS, 오름차순) — 역할 삭제 감사에 권한을 잃는 사용자를 남기기 위해 쓴다(WD-117). */
  public List<Long> findUserIdsByRoleId(Long roleId) {
    return dsl.select(USER_ROLE.USER_ID)
        .from(USER_ROLE)
        .where(USER_ROLE.ROLE_ID.eq(roleId))
        .orderBy(USER_ROLE.USER_ID)
        .fetch(USER_ROLE.USER_ID);
  }

  public void deleteById(Long id) {
    dsl.deleteFrom(ROLE).where(ROLE.ID.eq(id)).execute();
  }

  public void addPermission(Long roleId, Long permissionId) {
    dsl.insertInto(ROLE_PERMISSION)
        .set(ROLE_PERMISSION.ROLE_ID, roleId)
        .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
        .execute();
  }

  public void removePermission(Long roleId, Long permissionId) {
    dsl.deleteFrom(ROLE_PERMISSION)
        .where(
            ROLE_PERMISSION.ROLE_ID.eq(roleId).and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId)))
        .execute();
  }

  public void setPermissions(Long roleId, List<Long> permissionIds) {
    dsl.deleteFrom(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId)).execute();

    if (!permissionIds.isEmpty()) {
      var insert =
          dsl.insertInto(ROLE_PERMISSION, ROLE_PERMISSION.ROLE_ID, ROLE_PERMISSION.PERMISSION_ID);
      for (Long permissionId : permissionIds) {
        insert = insert.values(roleId, permissionId);
      }
      insert.execute();
    }
  }

  public List<RoleResponse> findByUserId(Long userId) {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .join(USER_ROLE)
        .on(USER_ROLE.ROLE_ID.eq(ROLE.ID))
        .where(USER_ROLE.USER_ID.eq(userId))
        .orderBy(ROLE.ID.asc())
        .fetch(this::mapToRoleResponse);
  }

  /**
   * 여러 사용자의 역할을 한 번의 쿼리로 조회한다 (#586).
   *
   * <p>목록 화면에서 사용자마다 {@link #findByUserId}를 호출하면 N+1 쿼리가 발생한다. 이 메서드는 {@code user_role.user_id} 를
   * 함께 조회해 사용자 ID → 역할 목록 맵으로 묶어 반환함으로써, 사용자 목록 조회 시 단일 배치 쿼리로 역할까지 채울 수 있게 한다.
   *
   * @param userIds 역할을 조회할 사용자 ID 목록
   * @return 사용자 ID → 역할 목록(ROLE.ID asc 정렬) 맵. 역할이 없는 사용자는 키 자체가 없다.
   */
  public Map<Long, List<RoleResponse>> findByUserIds(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    return dsl
        .select(USER_ROLE.USER_ID, ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .join(USER_ROLE)
        .on(USER_ROLE.ROLE_ID.eq(ROLE.ID))
        .where(USER_ROLE.USER_ID.in(userIds))
        .orderBy(USER_ROLE.USER_ID.asc(), ROLE.ID.asc())
        .fetch()
        .stream()
        .collect(
            Collectors.groupingBy(
                r -> r.get(USER_ROLE.USER_ID),
                LinkedHashMap::new,
                Collectors.mapping(this::mapToRoleResponse, Collectors.toList())));
  }
}

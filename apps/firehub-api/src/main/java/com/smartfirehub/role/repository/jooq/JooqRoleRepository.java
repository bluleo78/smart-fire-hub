package com.smartfirehub.role.repository.jooq;

import static com.smartfirehub.jooq.Tables.*;

import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
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

/** RoleRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@RequiredArgsConstructor
public class JooqRoleRepository implements RoleRepository {

  private final DSLContext dsl;

  private RoleResponse mapToRoleResponse(Record r) {
    return new RoleResponse(
        r.get(ROLE.ID), r.get(ROLE.NAME), r.get(ROLE.DESCRIPTION), r.get(ROLE.IS_SYSTEM));
  }

  @Override
  public List<RoleResponse> findAll() {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .orderBy(ROLE.ID.asc())
        .fetch(this::mapToRoleResponse);
  }

  @Override
  public Optional<RoleResponse> findById(Long id) {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .where(ROLE.ID.eq(id))
        .fetchOptional(this::mapToRoleResponse);
  }

  @Override
  public Optional<RoleResponse> findByName(String name) {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .where(ROLE.NAME.eq(name))
        .fetchOptional(this::mapToRoleResponse);
  }

  @Override
  public boolean existsByName(String name) {
    return dsl.fetchExists(dsl.selectOne().from(ROLE).where(ROLE.NAME.eq(name)));
  }

  @Override
  public Set<Long> findExistingIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(dsl.select(ROLE.ID).from(ROLE).where(ROLE.ID.in(ids)).fetch(ROLE.ID));
  }

  @Override
  public RoleResponse save(String name, String description) {
    return dsl.insertInto(ROLE)
        .set(ROLE.NAME, name)
        .set(ROLE.DESCRIPTION, description)
        .returning(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .fetchOne(this::mapToRoleResponse);
  }

  @Override
  public void update(Long id, String name, String description) {
    dsl.update(ROLE)
        .set(ROLE.NAME, name)
        .set(ROLE.DESCRIPTION, description)
        .set(ROLE.UPDATED_AT, LocalDateTime.now())
        .where(ROLE.ID.eq(id))
        .execute();
  }

  @Override
  public void lockById(Long id) {
    dsl.selectOne().from(ROLE).where(ROLE.ID.eq(id)).forUpdate().fetch();
  }

  @Override
  public List<Long> findUserIdsByRoleId(Long roleId) {
    return dsl.select(USER_ROLE.USER_ID)
        .from(USER_ROLE)
        .where(USER_ROLE.ROLE_ID.eq(roleId))
        .orderBy(USER_ROLE.USER_ID)
        .fetch(USER_ROLE.USER_ID);
  }

  @Override
  public int deleteById(Long id) {
    // 지운 행 수를 그대로 돌려준다 — 동시 삭제에서 진 쪽(0행)을 서비스가 404 로 가르기 위해서다(WD-582).
    return dsl.deleteFrom(ROLE).where(ROLE.ID.eq(id)).execute();
  }

  @Override
  public void addPermission(Long roleId, Long permissionId) {
    dsl.insertInto(ROLE_PERMISSION)
        .set(ROLE_PERMISSION.ROLE_ID, roleId)
        .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
        .execute();
  }

  @Override
  public void removePermission(Long roleId, Long permissionId) {
    dsl.deleteFrom(ROLE_PERMISSION)
        .where(
            ROLE_PERMISSION.ROLE_ID.eq(roleId).and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId)))
        .execute();
  }

  @Override
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

  @Override
  public List<RoleResponse> findByUserId(Long userId) {
    return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
        .from(ROLE)
        .join(USER_ROLE)
        .on(USER_ROLE.ROLE_ID.eq(ROLE.ID))
        .where(USER_ROLE.USER_ID.eq(userId))
        .orderBy(ROLE.ID.asc())
        .fetch(this::mapToRoleResponse);
  }

  @Override
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

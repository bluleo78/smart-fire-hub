package com.smartfirehub.permission.repository.jooq;

import static com.smartfirehub.jooq.Tables.*;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.securitylevel.repository.jooq.ActiveMembership;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/** PermissionRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@RequiredArgsConstructor
public class JooqPermissionRepository implements PermissionRepository {

  private final DSLContext dsl;

  private PermissionResponse mapToPermissionResponse(Record r) {
    return new PermissionResponse(
        r.get(PERMISSION.ID),
        r.get(PERMISSION.CODE),
        r.get(PERMISSION.DESCRIPTION),
        r.get(PERMISSION.CATEGORY));
  }

  @Override
  public List<PermissionResponse> findAll() {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .orderBy(PERMISSION.ID.asc())
        .fetch(this::mapToPermissionResponse);
  }

  @Override
  public List<PermissionResponse> findByCategory(String category) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .where(PERMISSION.CATEGORY.eq(category))
        .orderBy(PERMISSION.ID.asc())
        .fetch(this::mapToPermissionResponse);
  }

  @Override
  public Optional<PermissionResponse> findById(Long id) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .where(PERMISSION.ID.eq(id))
        .fetchOptional(this::mapToPermissionResponse);
  }

  @Override
  public Optional<PermissionResponse> findByCode(String code) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .where(PERMISSION.CODE.eq(code))
        .fetchOptional(this::mapToPermissionResponse);
  }

  @Override
  public List<PermissionResponse> findByRoleId(Long roleId) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .join(ROLE_PERMISSION)
        .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
        .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
        .orderBy(PERMISSION.ID.asc())
        .fetch(this::mapToPermissionResponse);
  }

  @Override
  public Set<String> findCodesByIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        dsl.select(PERMISSION.CODE)
            .from(PERMISSION)
            .where(PERMISSION.ID.in(ids))
            .fetch(PERMISSION.CODE));
  }

  @Override
  public boolean existsInCategory(Collection<Long> ids, String category) {
    if (ids.isEmpty()) {
      return false;
    }
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PERMISSION)
            .where(PERMISSION.ID.in(ids))
            .and(PERMISSION.CATEGORY.eq(category)));
  }

  @Override
  public Map<Long, Set<String>> findCodesByRoleIds(Collection<Long> roleIds) {
    if (roleIds.isEmpty()) {
      return Map.of();
    }
    return dsl
        .select(ROLE_PERMISSION.ROLE_ID, PERMISSION.CODE)
        .from(ROLE_PERMISSION)
        .join(PERMISSION)
        .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
        .where(ROLE_PERMISSION.ROLE_ID.in(roleIds))
        .fetchGroups(ROLE_PERMISSION.ROLE_ID, PERMISSION.CODE)
        .entrySet()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));
  }

  @Override
  public Set<String> findPermissionCodesByUserId(Long userId) {
    List<String> codes =
        dsl.selectDistinct(PERMISSION.CODE)
            .from(activeMemberGrants())
            .where(USER_ROLE.USER_ID.eq(userId))
            .fetch(r -> r.get(PERMISSION.CODE));
    return new HashSet<>(codes);
  }

  @Override
  public Set<Long> findActiveMemberIdsWithPermission(long tenantId, String code) {
    return dsl.selectDistinct(USER_ROLE.USER_ID)
        .from(activeMemberGrants())
        .where(PERMISSION.CODE.eq(code))
        .and(USER_ROLE.TENANT_ID.eq(tenantId))
        .fetchSet(USER_ROLE.USER_ID);
  }

  /**
   * 권한 → 역할 → 사용자 역할 → 계정(활성) → 멤버십(같은 테넌트 ACTIVE) 조인. 사용자 단위 권한 조회와 권한 단위 사용자 조회가 「유효한 권한」을 같은
   * 기준으로 보게 한 곳에 둔다 — 한쪽만 고치면 화면 권한과 알림 수신 범위가 서로 어긋난다. membership 은 전역 테이블이라 user_role.tenant_id 로
   * 짝을 맞춘다.
   */
  private Table<?> activeMemberGrants() {
    return PERMISSION
        .join(ROLE_PERMISSION)
        .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
        .join(USER_ROLE)
        .on(USER_ROLE.ROLE_ID.eq(ROLE_PERMISSION.ROLE_ID))
        .join(USER)
        .on(USER.ID.eq(USER_ROLE.USER_ID).and(USER.IS_ACTIVE.isTrue()))
        .join(ActiveMembership.TABLE)
        .on(ActiveMembership.of(USER_ROLE.USER_ID, USER_ROLE.TENANT_ID));
  }
}

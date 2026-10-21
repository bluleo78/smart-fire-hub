package com.smartfirehub.permission.repository;

import static com.smartfirehub.jooq.Tables.*;

import com.smartfirehub.permission.dto.PermissionResponse;
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
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PermissionRepository {

  private final DSLContext dsl;

  private PermissionResponse mapToPermissionResponse(Record r) {
    return new PermissionResponse(
        r.get(PERMISSION.ID),
        r.get(PERMISSION.CODE),
        r.get(PERMISSION.DESCRIPTION),
        r.get(PERMISSION.CATEGORY));
  }

  public List<PermissionResponse> findAll() {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .orderBy(PERMISSION.ID.asc())
        .fetch(this::mapToPermissionResponse);
  }

  public List<PermissionResponse> findByCategory(String category) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .where(PERMISSION.CATEGORY.eq(category))
        .orderBy(PERMISSION.ID.asc())
        .fetch(this::mapToPermissionResponse);
  }

  public Optional<PermissionResponse> findById(Long id) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .where(PERMISSION.ID.eq(id))
        .fetchOptional(this::mapToPermissionResponse);
  }

  public Optional<PermissionResponse> findByCode(String code) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .where(PERMISSION.CODE.eq(code))
        .fetchOptional(this::mapToPermissionResponse);
  }

  public List<PermissionResponse> findByRoleId(Long roleId) {
    return dsl.select(PERMISSION.ID, PERMISSION.CODE, PERMISSION.DESCRIPTION, PERMISSION.CATEGORY)
        .from(PERMISSION)
        .join(ROLE_PERMISSION)
        .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
        .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
        .orderBy(PERMISSION.ID.asc())
        .fetch(this::mapToPermissionResponse);
  }

  /**
   * 주어진 권한 id 들의 코드. 없는 id 는 결과에서 빠진다 — 역할 권한 교체 전에 "새로 추가되는 권한" 을 코드로 판정하기 위해 쓴다(WD-116).
   * permission 은 전역 카탈로그(RLS 없음)다.
   */
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

  /**
   * 여러 역할의 권한 코드를 한 번의 IN 질의로 모은다(역할 id → 코드 집합). 역할 부여 범위 검사가 바뀌는 역할마다 따로 조회하지 않게 하기 위해
   * 쓴다(WD-116).
   *
   * <p>role_permission 은 RLS 대상이라 현재 테넌트 역할만 보인다. 권한이 0개이거나 보이지 않는(없는·다른 테넌트) 역할은 결과에 키가 없다 — 호출자가
   * 빈 집합과 "역할 없음" 을 이것만으로 구분해서는 안 된다.
   */
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
  public Set<String> findPermissionCodesByUserId(Long userId) {
    var ms = DSL.table(DSL.name("membership"));
    var msUser = DSL.field(DSL.name("membership", "user_id"), Long.class);
    var msTenant = DSL.field(DSL.name("membership", "tenant_id"), Long.class);
    var msStatus = DSL.field(DSL.name("membership", "status"), String.class);
    List<String> codes =
        dsl.selectDistinct(PERMISSION.CODE)
            .from(PERMISSION)
            .join(ROLE_PERMISSION)
            .on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
            .join(USER_ROLE)
            .on(USER_ROLE.ROLE_ID.eq(ROLE_PERMISSION.ROLE_ID))
            .join(USER)
            .on(USER.ID.eq(USER_ROLE.USER_ID))
            .join(ms)
            .on(msUser.eq(USER_ROLE.USER_ID).and(msTenant.eq(USER_ROLE.TENANT_ID)))
            .where(USER_ROLE.USER_ID.eq(userId))
            .and(msStatus.eq("ACTIVE"))
            .and(USER.IS_ACTIVE.isTrue())
            .fetch(r -> r.get(PERMISSION.CODE));
    return new HashSet<>(codes);
  }
}

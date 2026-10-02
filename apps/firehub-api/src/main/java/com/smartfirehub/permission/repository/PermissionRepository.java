package com.smartfirehub.permission.repository;

import static com.smartfirehub.jooq.Tables.*;

import com.smartfirehub.permission.dto.PermissionResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
   * 사용자의 권한 코드(현재 테넌트, RLS).
   *
   * <p>ACTIVE 멤버십 조인(WD-2): 필터는 요청마다 멤버십을 다시 보지 않으므로, 이 조인이 없으면 정지된
   * 멤버가 access token 만료(최대 30분)까지 API 를 계속 쓴다. user_role.tenant_id 와 같은 테넌트의
   * 멤버십이 ACTIVE 일 때만 그 역할의 권한을 인정한다 — 정지 즉시 그 테넌트 권한만 0 이 된다.
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
            .join(ms)
            .on(msUser.eq(USER_ROLE.USER_ID).and(msTenant.eq(USER_ROLE.TENANT_ID)))
            .where(USER_ROLE.USER_ID.eq(userId))
            .and(msStatus.eq("ACTIVE"))
            .fetch(r -> r.get(PERMISSION.CODE));
    return new HashSet<>(codes);
  }
}

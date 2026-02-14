package com.smartfirehub.permission.repository;

import com.smartfirehub.permission.dto.PermissionResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.smartfirehub.jooq.Tables.*;

@Repository
public class PermissionRepository {

    private final DSLContext dsl;

    public PermissionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    private PermissionResponse mapToPermissionResponse(Record r) {
        return new PermissionResponse(
                r.get(PERMISSION.ID),
                r.get(PERMISSION.CODE),
                r.get(PERMISSION.DESCRIPTION),
                r.get(PERMISSION.CATEGORY)
        );
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
                .join(ROLE_PERMISSION).on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
                .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                .orderBy(PERMISSION.ID.asc())
                .fetch(this::mapToPermissionResponse);
    }

    public Set<String> findPermissionCodesByUserId(Long userId) {
        List<String> codes = dsl.selectDistinct(PERMISSION.CODE)
                .from(PERMISSION)
                .join(ROLE_PERMISSION).on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
                .join(USER_ROLE).on(USER_ROLE.ROLE_ID.eq(ROLE_PERMISSION.ROLE_ID))
                .where(USER_ROLE.USER_ID.eq(userId))
                .fetch(r -> r.get(PERMISSION.CODE));
        return new HashSet<>(codes);
    }
}

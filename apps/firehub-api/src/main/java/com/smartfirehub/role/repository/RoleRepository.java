package com.smartfirehub.role.repository;

import com.smartfirehub.role.dto.RoleResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.smartfirehub.jooq.Tables.*;

@Repository
public class RoleRepository {

    private final DSLContext dsl;

    public RoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    private RoleResponse mapToRoleResponse(Record r) {
        return new RoleResponse(
                r.get(ROLE.ID),
                r.get(ROLE.NAME),
                r.get(ROLE.DESCRIPTION),
                r.get(ROLE.IS_SYSTEM)
        );
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
        return dsl.fetchExists(
                dsl.selectOne().from(ROLE).where(ROLE.NAME.eq(name))
        );
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

    public void deleteById(Long id) {
        dsl.deleteFrom(ROLE)
                .where(ROLE.ID.eq(id))
                .execute();
    }

    public void addPermission(Long roleId, Long permissionId) {
        dsl.insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ROLE_ID, roleId)
                .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
                .execute();
    }

    public void removePermission(Long roleId, Long permissionId) {
        dsl.deleteFrom(ROLE_PERMISSION)
                .where(ROLE_PERMISSION.ROLE_ID.eq(roleId).and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId)))
                .execute();
    }

    public void setPermissions(Long roleId, List<Long> permissionIds) {
        dsl.deleteFrom(ROLE_PERMISSION)
                .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                .execute();

        for (Long permissionId : permissionIds) {
            dsl.insertInto(ROLE_PERMISSION)
                    .set(ROLE_PERMISSION.ROLE_ID, roleId)
                    .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
                    .execute();
        }
    }

    public List<RoleResponse> findByUserId(Long userId) {
        return dsl.select(ROLE.ID, ROLE.NAME, ROLE.DESCRIPTION, ROLE.IS_SYSTEM)
                .from(ROLE)
                .join(USER_ROLE).on(USER_ROLE.ROLE_ID.eq(ROLE.ID))
                .where(USER_ROLE.USER_ID.eq(userId))
                .orderBy(ROLE.ID.asc())
                .fetch(this::mapToRoleResponse);
    }
}

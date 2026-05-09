package com.smartfirehub.user.repository;

import static com.smartfirehub.jooq.Tables.*;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.trueCondition;
import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.user.dto.UserResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserRepository {

  private final DSLContext dsl;

  private UserResponse mapToUserResponse(Record r) {
    return new UserResponse(
        r.get(USER.ID),
        r.get(USER.USERNAME),
        r.get(USER.EMAIL),
        r.get(USER.NAME),
        r.get(USER.IS_ACTIVE),
        r.get(USER.CREATED_AT));
  }

  public Optional<UserResponse> findByUsername(String username) {
    return dsl.select(
            USER.ID, USER.USERNAME, USER.EMAIL, USER.NAME, USER.IS_ACTIVE, USER.CREATED_AT)
        .from(USER)
        .where(USER.USERNAME.eq(username))
        .fetchOptional(this::mapToUserResponse);
  }

  public Optional<UserResponse> findById(Long id) {
    return dsl.select(
            USER.ID, USER.USERNAME, USER.EMAIL, USER.NAME, USER.IS_ACTIVE, USER.CREATED_AT)
        .from(USER)
        .where(USER.ID.eq(id))
        .fetchOptional(this::mapToUserResponse);
  }

  public Optional<String> findPasswordByUsername(String username) {
    return dsl.select(USER.PASSWORD)
        .from(USER)
        .where(USER.USERNAME.eq(username))
        .fetchOptional(r -> r.get(USER.PASSWORD));
  }

  public Optional<String> findPasswordById(Long id) {
    return dsl.select(USER.PASSWORD)
        .from(USER)
        .where(USER.ID.eq(id))
        .fetchOptional(r -> r.get(USER.PASSWORD));
  }

  public boolean existsByUsername(String username) {
    return dsl.fetchExists(dsl.selectOne().from(USER).where(USER.USERNAME.eq(username)));
  }

  public boolean existsByEmail(String email) {
    return dsl.fetchExists(dsl.selectOne().from(USER).where(USER.EMAIL.eq(email)));
  }

  public boolean existsByEmailExcludingUser(String email, Long excludeUserId) {
    return dsl.fetchExists(
        dsl.selectOne().from(USER).where(USER.EMAIL.eq(email).and(USER.ID.ne(excludeUserId))));
  }

  public boolean existsById(Long id) {
    return dsl.fetchExists(dsl.selectOne().from(USER).where(USER.ID.eq(id)));
  }

  /**
   * Acquire a transaction-scoped advisory lock for first-user detection. Serializes concurrent
   * signup requests that might race for ADMIN role assignment. The lock is automatically released
   * when the transaction ends.
   */
  public void acquireFirstUserLock() {
    dsl.execute("SELECT pg_advisory_xact_lock({0})", val(1L));
  }

  public UserResponse save(String username, String email, String password, String name) {
    return dsl.insertInto(USER)
        .set(USER.USERNAME, username)
        .set(USER.EMAIL, email)
        .set(USER.PASSWORD, password)
        .set(USER.NAME, name)
        .returning(USER.ID, USER.USERNAME, USER.EMAIL, USER.NAME, USER.IS_ACTIVE, USER.CREATED_AT)
        .fetchOne(this::mapToUserResponse);
  }

  public List<UserResponse> findAllPaginated(String search, int page, int size) {
    Condition condition = trueCondition();

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      condition =
          condition.and(
              USER.USERNAME
                  .likeIgnoreCase(pattern, '\\')
                  .or(USER.NAME.likeIgnoreCase(pattern, '\\'))
                  .or(USER.EMAIL.likeIgnoreCase(pattern, '\\')));
    }

    return dsl.select(
            USER.ID, USER.USERNAME, USER.EMAIL, USER.NAME, USER.IS_ACTIVE, USER.CREATED_AT)
        .from(USER)
        .where(condition)
        .orderBy(USER.ID.asc())
        .limit(size)
        .offset(page * size)
        .fetch(this::mapToUserResponse);
  }

  public long countAll(String search) {
    Condition condition = trueCondition();

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      condition =
          condition.and(
              USER.USERNAME
                  .likeIgnoreCase(pattern, '\\')
                  .or(USER.NAME.likeIgnoreCase(pattern, '\\'))
                  .or(USER.EMAIL.likeIgnoreCase(pattern, '\\')));
    }

    return dsl.select(count()).from(USER).where(condition).fetchOne(0, Long.class);
  }

  public void update(Long id, String name, String email) {
    dsl.update(USER)
        .set(USER.NAME, name)
        .set(USER.EMAIL, email)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .execute();
  }

  public void updatePassword(Long id, String encodedPassword) {
    dsl.update(USER)
        .set(USER.PASSWORD, encodedPassword)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .execute();
  }

  public void setActive(Long id, boolean active) {
    dsl.update(USER)
        .set(USER.IS_ACTIVE, active)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .execute();
  }

  /**
   * 활성 ADMIN 사용자 수를 반환한다. 마지막 ADMIN 비활성화 방지 체크에 사용 (#146).
   *
   * @return is_active=true 이고 ADMIN 역할을 가진 사용자 수
   */
  public int countActiveAdmins() {
    return dsl.select(count())
        .from(USER)
        .join(USER_ROLE)
        .on(USER_ROLE.USER_ID.eq(USER.ID))
        .join(ROLE)
        .on(ROLE.ID.eq(USER_ROLE.ROLE_ID))
        .where(ROLE.NAME.eq("ADMIN").and(USER.IS_ACTIVE.isTrue()))
        .fetchOne(0, Integer.class);
  }

  /**
   * 특정 사용자가 ADMIN 역할을 갖고 있는지 확인한다. 마지막 ADMIN 비활성화 방지 체크에 사용 (#146).
   *
   * @param userId 확인할 사용자 ID
   * @return 해당 사용자가 ADMIN 역할을 보유하면 true
   */
  public boolean hasAdminRole(Long userId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(USER_ROLE)
            .join(ROLE)
            .on(ROLE.ID.eq(USER_ROLE.ROLE_ID))
            .where(USER_ROLE.USER_ID.eq(userId).and(ROLE.NAME.eq("ADMIN"))));
  }

  public void addRole(Long userId, Long roleId) {
    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, userId)
        .set(USER_ROLE.ROLE_ID, roleId)
        .execute();
  }

  public void removeRole(Long userId, Long roleId) {
    dsl.deleteFrom(USER_ROLE)
        .where(USER_ROLE.USER_ID.eq(userId).and(USER_ROLE.ROLE_ID.eq(roleId)))
        .execute();
  }

  public void setRoles(Long userId, List<Long> roleIds) {
    dsl.deleteFrom(USER_ROLE).where(USER_ROLE.USER_ID.eq(userId)).execute();

    if (!roleIds.isEmpty()) {
      var insert = dsl.insertInto(USER_ROLE, USER_ROLE.USER_ID, USER_ROLE.ROLE_ID);
      for (Long roleId : roleIds) {
        insert = insert.values(userId, roleId);
      }
      insert.execute();
    }
  }
}

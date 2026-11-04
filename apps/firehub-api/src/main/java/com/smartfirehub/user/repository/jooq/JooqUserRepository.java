package com.smartfirehub.user.repository.jooq;

import static com.smartfirehub.jooq.Tables.*;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.countDistinct;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectField;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/** UserRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@RequiredArgsConstructor
public class JooqUserRepository implements UserRepository {

  /**
   * 멤버십 테이블 참조 — jOOQ 코드젠 대상이 아니라 이름으로 만든다.
   *
   * <p>{@code membership} 은 {@code MembershipRepository} 도 같은 방식(=생성 클래스 없음)으로 참조한다. 테넌트 선택
   * <b>전에</b> 조회돼야 하는 전역 테이블이라 RLS 가 없고, 코드젠이 도는 {@code public} 스키마에 있으면서도 도메인 테이블과 다른 계층에 속한다.
   */
  private static final Table<?> MEMBERSHIP = table(name("membership"));

  private static final Field<Long> M_USER_ID = field(name("membership", "user_id"), Long.class);
  private static final Field<Long> M_TENANT_ID = field(name("membership", "tenant_id"), Long.class);
  private static final Field<String> M_STATUS = field(name("membership", "status"), String.class);

  private final DSLContext dsl;

  /** 가입·관리자 가드 직렬화 잠금 — PG advisory 호출을 플랫폼 잠금 추상화로 위임한다(WD-486). */
  private final PlatformLocks locks;

  /**
   * "이 사용자가 주어진 테넌트의 ACTIVE 멤버인가" 를 {@code "user"} 행에 대해 판정하는 술어.
   *
   * <p><b>왜 RLS 가 아니라 명시적 조인인가:</b> {@code "user"} 는 설계상 <b>전역</b> 테이블이다 (tenant_id 컬럼도, RLS 정책도 없다
   * — 한 사람이 여러 테넌트에 속할 수 있어야 하고, 로그인은 테넌트가 정해지기 <b>전에</b> 사용자를 찾아야 하기 때문이다). 즉 다른 도메인 테이블처럼 GUC 기반
   * RLS 로 덮을 수 없는 유일한 경로이며, 테넌트 경계는 이렇게 애플리케이션 쿼리에서 직접 세워야 한다. 이 술어가 빠지면 한 테넌트의 ADMIN 이 다른 테넌트 사용자를
   * 전부 열람·수정할 수 있다.
   *
   * <p>{@code tenant.status} 는 보지 않는다 — 테넌트 활성 여부는 로그인/테넌트 선택 시점의 게이트이고({@code
   * MembershipRepository.hasActiveMembership}), 여기까지 왔다는 것은 그 게이트를 이미 통과했다는 뜻이다. 목록 술어를 단순하게 유지한다.
   */
  private Condition memberOfTenant(long tenantId) {
    return exists(
        selectOne()
            .from(MEMBERSHIP)
            .where(M_USER_ID.eq(USER.ID))
            .and(M_TENANT_ID.eq(tenantId))
            .and(M_STATUS.eq("ACTIVE")));
  }

  /**
   * "이 사용자가 주어진 테넌트의 멤버인가(ACTIVE·SUSPENDED 모두)" — 관리 목록 전용 술어.
   *
   * <p>정지(SUSPENDED) 멤버도 관리자 목록에 보여야 재활성·제거할 수 있다(WD-2). 메시지 수신자 해석처럼 "지금 활동 중인 멤버" 가 필요한 경로는
   * {@link #memberOfTenant} 를 계속 쓴다.
   */
  private Condition inTenant(long tenantId) {
    return exists(
        selectOne().from(MEMBERSHIP).where(M_USER_ID.eq(USER.ID)).and(M_TENANT_ID.eq(tenantId)));
  }

  /**
   * {@link #mapToUserResponse} 가 읽는 컬럼 목록 — select·returning 이 공유한다. 한 곳만 컬럼이 빠지면 매퍼에서 런타임 오류가 나므로
   * 목록을 하나로 둔다.
   */
  private static final List<SelectField<?>> USER_FIELDS =
      List.of(
          USER.ID,
          USER.USERNAME,
          USER.EMAIL,
          USER.NAME,
          USER.IS_ACTIVE,
          USER.CREATED_AT,
          USER.MUST_CHANGE_PASSWORD);

  private UserResponse mapToUserResponse(Record r) {
    return new UserResponse(
        r.get(USER.ID),
        r.get(USER.USERNAME),
        r.get(USER.EMAIL),
        r.get(USER.NAME),
        r.get(USER.IS_ACTIVE),
        r.get(USER.CREATED_AT),
        Boolean.TRUE.equals(r.get(USER.MUST_CHANGE_PASSWORD)));
  }

  @Override
  public Optional<UserResponse> findByUsername(String username) {
    return dsl.select(USER_FIELDS)
        .from(USER)
        .where(USER.USERNAME.eq(username))
        .fetchOptional(this::mapToUserResponse);
  }

  @Override
  public Optional<UserResponse> findById(Long id) {
    return dsl.select(USER_FIELDS)
        .from(USER)
        .where(USER.ID.eq(id))
        .fetchOptional(this::mapToUserResponse);
  }

  @Override
  public String resolveLoginUsername(String input) {
    String trimmed = input.trim();
    if (dsl.fetchExists(dsl.selectOne().from(USER).where(USER.USERNAME.eq(trimmed)))) {
      return trimmed;
    }
    return findByUsernameIgnoreCase(trimmed)
        .map(UserResponse::username)
        .orElse(trimmed.toLowerCase(Locale.ROOT));
  }

  @Override
  public Optional<String> findPasswordByUsername(String username) {
    return dsl.select(USER.PASSWORD)
        .from(USER)
        .where(USER.USERNAME.eq(username))
        .fetchOptional(r -> r.get(USER.PASSWORD));
  }

  @Override
  public Optional<String> findPasswordById(Long id) {
    return dsl.select(USER.PASSWORD)
        .from(USER)
        .where(USER.ID.eq(id))
        .fetchOptional(r -> r.get(USER.PASSWORD));
  }

  @Override
  public boolean existsByUsername(String username) {
    return dsl.fetchExists(dsl.selectOne().from(USER).where(USER.USERNAME.eq(username)));
  }

  @Override
  public boolean existsAnyUser() {
    return dsl.fetchExists(dsl.selectOne().from(USER));
  }

  @Override
  public void acquireFirstUserLock() {
    locks.xactLock(1L);
  }

  @Override
  public void acquireMemberAdminGuardLock(long tenantId) {
    locks.xactLock("member-admin-guard:" + tenantId);
  }

  @Override
  public UserResponse save(String username, String email, String password, String name) {
    return insert(username, email, password, name, false);
  }

  /** 계정 삽입 공통부. mustChangePassword 만 경로(가입 vs 관리자 임시 비밀번호)마다 다르다. */
  private UserResponse insert(
      String username, String email, String password, String name, boolean mustChangePassword) {
    return dsl.insertInto(USER)
        .set(USER.USERNAME, username)
        .set(USER.EMAIL, email)
        .set(USER.PASSWORD, password)
        .set(USER.NAME, name)
        .set(USER.MUST_CHANGE_PASSWORD, mustChangePassword)
        .returning(USER_FIELDS)
        .fetchOne(this::mapToUserResponse);
  }

  @Override
  public Optional<UserResponse> findByUsernameIgnoreCase(String username) {
    return dsl.select(USER_FIELDS)
        .from(USER)
        .where(lower(USER.USERNAME).eq(username.toLowerCase(Locale.ROOT)))
        .orderBy(USER.ID.asc())
        .limit(1)
        .fetchOptional(this::mapToUserResponse);
  }

  @Override
  public UserResponse saveWithTemporaryPassword(
      String username, String email, String encodedPassword, String name) {
    return insert(username, email, encodedPassword, name, true);
  }

  @Override
  public List<UserResponse> findAllPaginated(long tenantId, String search, int page, int size) {
    Condition condition = inTenant(tenantId);

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      condition =
          condition.and(
              USER.USERNAME
                  .likeIgnoreCase(pattern, '\\')
                  .or(USER.NAME.likeIgnoreCase(pattern, '\\'))
                  .or(USER.EMAIL.likeIgnoreCase(pattern, '\\')));
    }

    return dsl.select(USER_FIELDS)
        .from(USER)
        .where(condition)
        .orderBy(USER.ID.asc())
        .limit(size)
        .offset((long) page * size)
        .fetch(this::mapToUserResponse);
  }

  @Override
  public List<UserResponse> findByIds(long tenantId, List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Condition condition = memberOfTenant(tenantId).and(USER.ID.in(ids));
    return dsl.select(USER_FIELDS).from(USER).where(condition).fetch(this::mapToUserResponse);
  }

  @Override
  public long countAll(long tenantId, String search) {
    Condition condition = inTenant(tenantId);

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

  @Override
  public boolean update(Long id, String name, String email) {
    return dsl.update(USER)
            .set(USER.NAME, name)
            .set(USER.EMAIL, email)
            .set(USER.UPDATED_AT, LocalDateTime.now())
            .where(USER.ID.eq(id))
            .execute()
        > 0;
  }

  @Override
  public void updatePassword(Long id, String encodedPassword) {
    dsl.update(USER)
        .set(USER.PASSWORD, encodedPassword)
        .set(USER.MUST_CHANGE_PASSWORD, false)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .execute();
  }

  @Override
  public int setActive(Long id, boolean active) {
    return dsl.update(USER)
        .set(USER.IS_ACTIVE, active)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .and(USER.IS_ACTIVE.ne(active))
        .execute();
  }

  @Override
  public int countActiveAdmins(long tenantId) {
    return dsl.select(countDistinct(USER.ID))
        .from(activeAdminsOf(tenantId))
        .where(ACTIVE_ADMIN_CONDITION)
        .fetchOne(0, Integer.class);
  }

  @Override
  public List<Long> findActiveAdminIds(long tenantId) {
    return dsl.selectDistinct(USER.ID)
        .from(activeAdminsOf(tenantId))
        .where(ACTIVE_ADMIN_CONDITION)
        .orderBy(USER.ID)
        .fetch(USER.ID);
  }

  /**
   * 활성 ADMIN 판정의 조인 — 세는 쪽과 고르는 쪽이 같은 정의를 쓰게 한 곳에 둔다(WD-169). tenant_id 를 명시한다(membership 은 RLS 가
   * 없다).
   */
  private Table<?> activeAdminsOf(long tenantId) {
    return USER.join(USER_ROLE)
        .on(USER_ROLE.USER_ID.eq(USER.ID).and(USER_ROLE.TENANT_ID.eq(tenantId)))
        .join(ROLE)
        .on(ROLE.ID.eq(USER_ROLE.ROLE_ID).and(ROLE.TENANT_ID.eq(tenantId)))
        .join(MEMBERSHIP)
        .on(M_USER_ID.eq(USER.ID).and(M_TENANT_ID.eq(tenantId)));
  }

  /** 활성 ADMIN 판정의 조건 — ADMIN 역할 + 이 테넌트 멤버십 ACTIVE + 전역 계정 활성. */
  private static final Condition ACTIVE_ADMIN_CONDITION =
      ROLE.NAME.eq("ADMIN").and(M_STATUS.eq("ACTIVE")).and(USER.IS_ACTIVE.isTrue());

  @Override
  public int deleteRolesInTenant(Long userId, long tenantId) {
    return dsl.deleteFrom(USER_ROLE)
        .where(USER_ROLE.USER_ID.eq(userId).and(USER_ROLE.TENANT_ID.eq(tenantId)))
        .execute();
  }

  @Override
  public boolean hasAdminRole(Long userId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(USER_ROLE)
            .join(ROLE)
            .on(ROLE.ID.eq(USER_ROLE.ROLE_ID))
            .where(USER_ROLE.USER_ID.eq(userId).and(ROLE.NAME.eq("ADMIN"))));
  }

  @Override
  public void addRole(Long userId, Long roleId) {
    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, userId)
        .set(USER_ROLE.ROLE_ID, roleId)
        .execute();
  }

  @Override
  public void removeRole(Long userId, Long roleId) {
    dsl.deleteFrom(USER_ROLE)
        .where(USER_ROLE.USER_ID.eq(userId).and(USER_ROLE.ROLE_ID.eq(roleId)))
        .execute();
  }

  @Override
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

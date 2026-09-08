package com.smartfirehub.user.repository;

import static com.smartfirehub.jooq.Tables.*;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.user.dto.UserResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserRepository {

  /**
   * 멤버십 테이블 참조 — jOOQ 코드젠 대상이 아니라 이름으로 만든다.
   *
   * <p>{@code membership} 은 {@code MembershipRepository} 도 같은 방식(=생성 클래스 없음)으로
   * 참조한다. 테넌트 선택 <b>전에</b> 조회돼야 하는 전역 테이블이라 RLS 가 없고, 코드젠이 도는
   * {@code public} 스키마에 있으면서도 도메인 테이블과 다른 계층에 속한다.
   */
  private static final Table<?> MEMBERSHIP = table(name("membership"));

  private static final Field<Long> M_USER_ID = field(name("membership", "user_id"), Long.class);
  private static final Field<Long> M_TENANT_ID = field(name("membership", "tenant_id"), Long.class);
  private static final Field<String> M_STATUS = field(name("membership", "status"), String.class);

  private final DSLContext dsl;

  /**
   * "이 사용자가 주어진 테넌트의 ACTIVE 멤버인가" 를 {@code "user"} 행에 대해 판정하는 술어.
   *
   * <p><b>왜 RLS 가 아니라 명시적 조인인가:</b> {@code "user"} 는 설계상 <b>전역</b> 테이블이다
   * (tenant_id 컬럼도, RLS 정책도 없다 — 한 사람이 여러 테넌트에 속할 수 있어야 하고, 로그인은
   * 테넌트가 정해지기 <b>전에</b> 사용자를 찾아야 하기 때문이다). 즉 다른 도메인 테이블처럼 GUC
   * 기반 RLS 로 덮을 수 없는 유일한 경로이며, 테넌트 경계는 이렇게 애플리케이션 쿼리에서 직접
   * 세워야 한다. 이 술어가 빠지면 한 테넌트의 ADMIN 이 다른 테넌트 사용자를 전부 열람·수정할 수
   * 있다.
   *
   * <p>{@code tenant.status} 는 보지 않는다 — 테넌트 활성 여부는 로그인/테넌트 선택 시점의
   * 게이트이고({@code MembershipRepository.hasActiveMembership}), 여기까지 왔다는 것은 그 게이트를
   * 이미 통과했다는 뜻이다. 목록 술어를 단순하게 유지한다.
   */
  private Condition memberOfTenant(long tenantId) {
    return exists(
        selectOne()
            .from(MEMBERSHIP)
            .where(M_USER_ID.eq(USER.ID))
            .and(M_TENANT_ID.eq(tenantId))
            .and(M_STATUS.eq("ACTIVE")));
  }

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

  /**
   * 사용자가 한 명이라도 있는지 (테넌트 무관, 전역).
   *
   * <p>회원가입의 "첫 사용자에게 ADMIN 부여" 판정에만 쓴다. 예전에는 {@code countAll(null) == 0}
   * 이었는데, {@code countAll} 이 현재 테넌트 소속으로 좁혀지면서 그 판정이 "이 테넌트의 첫 멤버"
   * 로 바뀌어 버린다 — 부트스트랩 의미(시스템 최초 사용자)와 다르다. 전역이라는 사실을 이름으로
   * 드러내 분리한다.
   */
  public boolean existsAnyUser() {
    return dsl.fetchExists(dsl.selectOne().from(USER));
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

  /**
   * 사용자 목록 — <b>현재 테넌트에 ACTIVE 멤버십이 있는 사용자만</b>.
   *
   * <p>tenantId 를 인자로 받는 이유: 이 코드베이스의 리포지토리는 {@code TenantContext} 를 직접
   * 읽지 않는다({@code MembershipRepository} 도 인자로 받는다). ThreadLocal 과 트랜잭션 GUC 가
   * 어긋날 수 있는 경로가 실제로 존재하므로({@code CurrentTransactionTenant} 참고), 테넌트 해석은
   * 트랜잭션 경계를 아는 서비스 레이어에서 한 번만 한다.
   */
  public List<UserResponse> findAllPaginated(long tenantId, String search, int page, int size) {
    Condition condition = memberOfTenant(tenantId);

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

  /**
   * 주어진 ID들에 해당하는 사용자를 현재 테넌트 멤버로 한정하여 조회한다 (#555).
   *
   * <p>알림 수신자 등 이미 ID로 저장된 값을 이름/이메일로 되살릴 때(하이드레이션) 사용 —
   * {@link #findAllPaginated}는 검색어 기반이라 검색을 거치지 않은 기존 선택값은 찾지 못한다.
   */
  public List<UserResponse> findByIds(long tenantId, List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Condition condition = memberOfTenant(tenantId).and(USER.ID.in(ids));
    return dsl.select(
            USER.ID, USER.USERNAME, USER.EMAIL, USER.NAME, USER.IS_ACTIVE, USER.CREATED_AT)
        .from(USER)
        .where(condition)
        .fetch(this::mapToUserResponse);
  }

  /** {@link #findAllPaginated} 의 총건수. 같은 테넌트 술어를 반드시 함께 적용해야 페이지가 맞는다. */
  public long countAll(long tenantId, String search) {
    Condition condition = memberOfTenant(tenantId);

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

package com.smartfirehub.user.repository;

import static com.smartfirehub.jooq.Tables.*;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.countDistinct;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.user.dto.UserResponse;
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

@Repository
@RequiredArgsConstructor
public class UserRepository {

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

  public Optional<UserResponse> findByUsername(String username) {
    return dsl.select(USER_FIELDS)
        .from(USER)
        .where(USER.USERNAME.eq(username))
        .fetchOptional(this::mapToUserResponse);
  }

  public Optional<UserResponse> findById(Long id) {
    return dsl.select(USER_FIELDS)
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

  /**
   * 이메일 대소문자 무시 존재 검사 — 멤버 추가 전용(WD-2 리뷰 지적 4).
   *
   * <p>과거 계정은 이메일을 대소문자 섞어 저장했을 수 있다(username 은 다른 값). 정확 일치만 보면 관리자가 소문자로 추가할 때 같은 사람의 두 번째 계정이
   * 생긴다. 가입 경로({@code existsByEmail})는 의미를 바꾸지 않으려 별도 메서드로 둔다.
   *
   * @param email 소문자로 정규화된 이메일
   */
  public boolean existsByEmailIgnoreCase(String email) {
    return dsl.fetchExists(
        dsl.selectOne().from(USER).where(lower(USER.EMAIL).eq(email.toLowerCase(Locale.ROOT))));
  }

  public boolean existsByEmailExcludingUser(String email, Long excludeUserId) {
    return dsl.fetchExists(
        dsl.selectOne().from(USER).where(USER.EMAIL.eq(email).and(USER.ID.ne(excludeUserId))));
  }

  /**
   * 사용자가 한 명이라도 있는지 (테넌트 무관, 전역).
   *
   * <p>회원가입의 "첫 사용자에게 ADMIN 부여" 판정에만 쓴다. 예전에는 {@code countAll(null) == 0} 이었는데, {@code countAll} 이
   * 현재 테넌트 소속으로 좁혀지면서 그 판정이 "이 테넌트의 첫 멤버" 로 바뀌어 버린다 — 부트스트랩 의미(시스템 최초 사용자)와 다르다. 전역이라는 사실을 이름으로 드러내
   * 분리한다.
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

  /**
   * 테넌트 단위 "마지막 활성 ADMIN" 판정 직렬화 잠금(트랜잭션 범위, WD-2 리뷰 지적 3).
   *
   * <p>두 관리자가 서로를 동시에 정지·제거하면 각자 "활성 ADMIN 2명" 을 보고 통과해 0 명이 될 수 있다. 판정 전에 이 잠금을 잡으면 두 번째 트랜잭션은 첫
   * 번째 커밋 뒤의 수를 본다(READ COMMITTED). 키는 접두어를 붙인 문자열 해시다 — 가입 잠금({@link #acquireFirstUserLock} 의 키
   * 1)과 같은 숫자 키 공간을 쓰지만 테넌트 id 를 그대로 키로 쓰면 테넌트 1 이 가입 잠금과 충돌하므로 네임스페이스를 나눈다.
   */
  public void acquireMemberAdminGuardLock(long tenantId) {
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended({0}, 0))",
        val("member-admin-guard:" + tenantId));
  }

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

  /**
   * username 대소문자 무시 조회 — 멤버 추가 시 "이미 가입된 계정인가" 판정용.
   *
   * <p>username 은 이메일이고 사람마다 대소문자를 섞어 입력한다. 정확 일치만 보면 같은 사람의 두 번째 계정이 생긴다. 같은 값이 대소문자만 달리 두 행 있는 과거
   * 데이터는 id 가 작은 쪽을 쓴다.
   */
  public Optional<UserResponse> findByUsernameIgnoreCase(String username) {
    return dsl.select(USER_FIELDS)
        .from(USER)
        .where(lower(USER.USERNAME).eq(username.toLowerCase(Locale.ROOT)))
        .orderBy(USER.ID.asc())
        .limit(1)
        .fetchOptional(this::mapToUserResponse);
  }

  /** 관리자가 임시 비밀번호로 만드는 계정 — 첫 로그인 시 변경 강제 표식을 켠 채 저장한다. */
  public UserResponse saveWithTemporaryPassword(
      String username, String email, String encodedPassword, String name) {
    return insert(username, email, encodedPassword, name, true);
  }

  /**
   * 사용자 목록 — <b>현재 테넌트의 멤버(ACTIVE·SUSPENDED 모두)</b>. 정지 멤버도 보여야 재활성·제거할 수 있다.
   *
   * <p>tenantId 를 인자로 받는 이유: 이 코드베이스의 리포지토리는 {@code TenantContext} 를 직접 읽지 않는다({@code
   * MembershipRepository} 도 인자로 받는다). ThreadLocal 과 트랜잭션 GUC 가 어긋날 수 있는 경로가 실제로 존재하므로({@code
   * CurrentTransactionTenant} 참고), 테넌트 해석은 트랜잭션 경계를 아는 서비스 레이어에서 한 번만 한다.
   */
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
        .offset(page * size)
        .fetch(this::mapToUserResponse);
  }

  /**
   * 주어진 ID들에 해당하는 사용자를 현재 테넌트 멤버로 한정하여 조회한다 (#555).
   *
   * <p>알림 수신자 등 이미 ID로 저장된 값을 이름/이메일로 되살릴 때(하이드레이션) 사용 — {@link #findAllPaginated}는 검색어 기반이라 검색을
   * 거치지 않은 기존 선택값은 찾지 못한다.
   */
  public List<UserResponse> findByIds(long tenantId, List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Condition condition = memberOfTenant(tenantId).and(USER.ID.in(ids));
    return dsl.select(USER_FIELDS).from(USER).where(condition).fetch(this::mapToUserResponse);
  }

  /** {@link #findAllPaginated} 의 총건수. 같은 테넌트 술어를 반드시 함께 적용해야 페이지가 맞는다. */
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

  public void update(Long id, String name, String email) {
    dsl.update(USER)
        .set(USER.NAME, name)
        .set(USER.EMAIL, email)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .execute();
  }

  /** 비밀번호를 바꾸고, 임시 비밀번호 표식도 내린다(본인 변경이 유일한 호출처). */
  public void updatePassword(Long id, String encodedPassword) {
    dsl.update(USER)
        .set(USER.PASSWORD, encodedPassword)
        .set(USER.MUST_CHANGE_PASSWORD, false)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .execute();
  }

  // 전역 계정 활성 플래그 — 운영자 콘솔 계정 비활성화/재활성화(#784, PlatformAccountService)만 쓴다. 테넌트 정지는 멤버십.
  public void setActive(Long id, boolean active) {
    dsl.update(USER)
        .set(USER.IS_ACTIVE, active)
        .set(USER.UPDATED_AT, LocalDateTime.now())
        .where(USER.ID.eq(id))
        .execute();
  }

  /**
   * 이 테넌트의 "활성 ADMIN" 수 — 잠금 방지(마지막 ADMIN 정지·제거 금지) 판정용 (#146, WD-2).
   *
   * <p>활성 = 이 테넌트 멤버십 ACTIVE + 이 테넌트 ADMIN 역할 + 전역 계정 활성. 예전 구현은 전역 {@code user.is_active} 만 봐서, 이
   * 테넌트에서 정지된 ADMIN 도 "활성" 으로 세어 마지막 활성 ADMIN 정지를 허용했다. RLS 에 기대지 않고 tenant_id 를 명시하는 이유:
   * membership 은 RLS 가 없다.
   */
  public int countActiveAdmins(long tenantId) {
    return dsl.select(countDistinct(USER.ID))
        .from(USER)
        .join(USER_ROLE)
        .on(USER_ROLE.USER_ID.eq(USER.ID).and(USER_ROLE.TENANT_ID.eq(tenantId)))
        .join(ROLE)
        .on(ROLE.ID.eq(USER_ROLE.ROLE_ID).and(ROLE.TENANT_ID.eq(tenantId)))
        .join(MEMBERSHIP)
        .on(M_USER_ID.eq(USER.ID).and(M_TENANT_ID.eq(tenantId)))
        .where(ROLE.NAME.eq("ADMIN"))
        .and(M_STATUS.eq("ACTIVE"))
        .and(USER.IS_ACTIVE.isTrue())
        .fetchOne(0, Integer.class);
  }

  /** 이 테넌트에서 사용자의 역할 배정을 모두 지운다(멤버 제거). RLS 와 별개로 tenant_id 를 명시한다. */
  public int deleteRolesInTenant(Long userId, long tenantId) {
    return dsl.deleteFrom(USER_ROLE)
        .where(USER_ROLE.USER_ID.eq(userId).and(USER_ROLE.TENANT_ID.eq(tenantId)))
        .execute();
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

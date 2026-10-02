package com.smartfirehub.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.AddMemberRequest;
import com.smartfirehub.user.dto.AddMemberResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 멤버 추가(WD-2) 서비스 계약. 비트랜잭션 — 새 테넌트 A·B 를 만들어 테넌트 경계를 실제 RLS 로 검증한다.
 * 프로덕션 호출은 {@code TenantContext.runScopedGet(tenantA, ...)} 로만 감싸고 inTenantFixture 로 감싸지
 * 않는다(IntegrationTestBase:73-77 규칙).
 */
class MemberAddServiceTest extends IntegrationTestBase {

  private static final String TEMP = "TempPass1x";

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private MembershipRepository membershipRepository;
  @Autowired private TenantProvisioningService provisioningService;

  private long tenantA;
  private long tenantB;
  private long callerId;
  private final List<Long> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd2-add-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "wd2-add-b");
    provisioningService.provisionDefaults(tenantA);
    provisioningService.provisionDefaults(tenantB);
    callerId = newMember(tenantA, "caller");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    List<Runnable> steps = new ArrayList<>();
    for (Long id : createdUserIds) {
      steps.add(
          () ->
              TestUsers.cleanup(
                  dsl, fixtureTransactionTemplate, id, tenantA, tenantB, DEFAULT_TEST_TENANT_ID));
    }
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantA));
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantB));
    TenantRlsTestSupport.cleanupAll(steps.toArray(Runnable[]::new));
  }

  private long newMember(long tenantId, String prefix) {
    String username = prefix + "-" + System.nanoTime() + "@example.com";
    long id =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                username,
                username,
                "Password123",
                prefix,
                tenantId)
            .id();
    createdUserIds.add(id);
    return id;
  }

  /** 역할 id 는 테넌트마다 다르고 role 은 RLS 대상이라, 반드시 그 테넌트 컨텍스트 안에서 조회한다. */
  private long roleId(long tenantId, String name) {
    return inTenantFixture(
        tenantId,
        () -> ((Number) dsl.fetchValue("select id from role where name = ?", name)).longValue());
  }

  private List<String> roleNames(long tenantId, long userId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetch(
                    "select r.name from user_role ur join role r on r.id = ur.role_id where ur.user_id = ? order by r.name",
                    userId)
                .getValues(0, String.class));
  }

  private AddMemberResponse addInA(AddMemberRequest req) {
    AddMemberResponse res =
        TenantContext.runScopedGet(tenantA, () -> userService.addMember(req, callerId));
    if (res.created()) {
      createdUserIds.add(res.userId());
    }
    return res;
  }

  @Test
  void addMember_newAccount_createsUserMembershipRolesAndFlag() {
    String email = "newbie-" + System.nanoTime() + "@acme.io";

    AddMemberResponse res = addInA(new AddMemberRequest(email, "뉴비", TEMP, List.of()));

    assertThat(res.created()).isTrue();
    var user = userRepository.findById(res.userId()).orElseThrow();
    assertThat(user.username()).isEqualTo(email);
    assertThat(user.mustChangePassword()).isTrue();
    assertThat(
            passwordEncoder.matches(
                TEMP, userRepository.findPasswordById(res.userId()).orElseThrow()))
        .isTrue();
    var m = membershipRepository.findInTenant(res.userId(), tenantA).orElseThrow();
    assertThat(m.role()).isEqualTo("MEMBER");
    assertThat(m.status()).isEqualTo("ACTIVE");
    assertThat(roleNames(tenantA, res.userId())).containsExactly("USER");
    // 기본 테넌트 자동 소속 없음(스펙 2.1).
    assertThat(membershipRepository.findInTenant(res.userId(), DEFAULT_TEST_TENANT_ID)).isEmpty();
  }

  @Test
  void addMember_customRoleOnly_stillGetsUserRole() {
    String email = "admin-only-" + System.nanoTime() + "@acme.io";
    long adminRoleA = roleId(tenantA, "ADMIN");

    AddMemberResponse res = addInA(new AddMemberRequest(email, "관리자만", TEMP, List.of(adminRoleA)));

    // USER 는 워크스페이스 기본 역할 — 선택 역할은 USER 에 '추가'된다.
    assertThat(roleNames(tenantA, res.userId())).containsExactly("ADMIN", "USER");
  }

  @Test
  void addMember_existingAccountCaseInsensitive() {
    String username = "Jisu-" + System.nanoTime() + "@Other.IO";
    long existing = newMember(tenantB, "placeholder");
    dsl.execute(
        "update \"user\" set username = ?, email = ? where id = ?", username, username, existing);

    AddMemberResponse res =
        addInA(new AddMemberRequest(username.toLowerCase(), "다른이름", "Ignored1Pass", List.of()));

    assertThat(res.created()).isFalse();
    assertThat(res.userId()).isEqualTo(existing);
    var user = userRepository.findById(existing).orElseThrow();
    assertThat(user.name()).isEqualTo("placeholder"); // 이름 불변
    assertThat(user.mustChangePassword()).isFalse();
    assertThat(
            passwordEncoder.matches(
                "Password123", userRepository.findPasswordById(existing).orElseThrow()))
        .isTrue();
    assertThat(membershipRepository.findInTenant(existing, tenantA)).isPresent();
    assertThat(roleNames(tenantA, existing)).containsExactly("USER");
  }

  @Test
  void addMember_alreadyActiveMember_conflict() {
    long member = newMember(tenantA, "dup");
    String username = userRepository.findById(member).orElseThrow().username();

    assertThatThrownBy(() -> addInA(new AddMemberRequest(username, "x", TEMP, List.of())))
        .isInstanceOf(CodedApiException.class)
        .extracting("code")
        .isEqualTo("MEMBER_ALREADY_EXISTS");
  }

  /**
   * 동시 추가 경합 재현: 사전 검사(멤버십 없음)는 통과하지만 INSERT 에서 유니크 제약이 걸리는 상황.
   * 멤버십 없이 user_role(USER) 만 남은 사용자를 만들어 결정적으로 재현한다. 영어 무결성 오류가 아니라
   * 409 MEMBER_ALREADY_EXISTS 여야 하고, 같은 트랜잭션에서 넣은 멤버십은 롤백돼야 한다.
   */
  @Test
  void addMember_duplicateKeyRace_mapsTo409MemberAlreadyExists() {
    long stray = newMember(tenantB, "stray");
    String username = userRepository.findById(stray).orElseThrow().username();
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, stray, tenantA, "USER");

    assertThatThrownBy(() -> addInA(new AddMemberRequest(username, "x", TEMP, List.of())))
        .isInstanceOf(CodedApiException.class)
        .extracting("code")
        .isEqualTo("MEMBER_ALREADY_EXISTS");
    assertThat(membershipRepository.findInTenant(stray, tenantA)).isEmpty();
  }

  @Test
  void addMember_suspendedMember_conflictWithUserId() {
    long member = newMember(tenantA, "susp");
    membershipRepository.updateStatus(member, tenantA, "SUSPENDED");
    String username = userRepository.findById(member).orElseThrow().username();

    assertThatThrownBy(() -> addInA(new AddMemberRequest(username, "x", TEMP, List.of())))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MEMBER_SUSPENDED");
              assertThat(e.details()).containsEntry("userId", String.valueOf(member));
            });
  }

  @Test
  void addMember_otherTenantRoleId_badRequestAndNothingCreated() {
    String email = "badrole-" + System.nanoTime() + "@acme.io";
    long adminRoleB = roleId(tenantB, "ADMIN");

    assertThatThrownBy(() -> addInA(new AddMemberRequest(email, "x", TEMP, List.of(adminRoleB))))
        .isInstanceOf(CodedApiException.class)
        .extracting("code")
        .isEqualTo("INVALID_ROLE");
    assertThat(userRepository.findByUsernameIgnoreCase(email)).isEmpty();
  }

  @Test
  void addMember_emailUsedByAnotherUsername_conflict() {
    long other = newMember(tenantB, "owner-of-email");
    String email = "shared-" + System.nanoTime() + "@acme.io";
    dsl.execute("update \"user\" set email = ? where id = ?", email, other);

    assertThatThrownBy(() -> addInA(new AddMemberRequest(email, "x", TEMP, List.of())))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  /**
   * 리뷰 지적 4: 과거 계정의 이메일이 대소문자를 섞어 저장돼 있고(username 은 다른 값) 관리자가 소문자로 추가하면,
   * 정확 일치 검사로는 못 잡아 같은 사람의 두 번째 계정이 생긴다. 대소문자 무시로 막아야 한다.
   */
  @Test
  void addMember_emailUsedByAnotherUsername_mixedCase_conflict() {
    long legacy = newMember(tenantB, "legacy-owner");
    String stored = "Legacy-" + System.nanoTime() + "@Example.com";
    dsl.execute("update \"user\" set email = ? where id = ?", stored, legacy);
    String lower = stored.toLowerCase(java.util.Locale.ROOT);

    assertThatThrownBy(() -> addInA(new AddMemberRequest(lower, "x", TEMP, List.of())))
        .isInstanceOf(EmailAlreadyExistsException.class);
    assertThat(userRepository.findByUsernameIgnoreCase(lower)).isEmpty();
  }

  @Test
  void addMember_writesAuditLogWithCreatedFlag() {
    String email = "audit-" + System.nanoTime() + "@acme.io";
    AddMemberResponse res = addInA(new AddMemberRequest(email, "감사", TEMP, List.of()));

    String metadata =
        inTenantFixture(
            tenantA,
            () ->
                dsl.fetchValue(
                        "select metadata::text from audit_log where action_type = 'MEMBER_ADD' and resource_id = ?",
                        String.valueOf(res.userId()))
                    .toString());
    assertThat(metadata).contains("\"created\": true");
  }
}

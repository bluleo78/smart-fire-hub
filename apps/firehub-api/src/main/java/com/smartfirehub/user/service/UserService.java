package com.smartfirehub.user.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.tenant.dto.TenantMembership;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.AddMemberRequest;
import com.smartfirehub.user.dto.AddMemberResponse;
import com.smartfirehub.user.dto.UserDetailResponse;
import com.smartfirehub.user.dto.UserListResponse;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 관리 서비스.
 *
 * <p><b>테넌트 경계에 대하여.</b> {@code "user"} 는 전역 테이블(tenant_id 없음, RLS 없음)이라 다른
 * 도메인처럼 RLS 가 알아서 격리해 주지 않는다. 그래서 <b>관리 경로</b>(목록·상세·역할부여·활성화)는
 * 여기서 명시적으로 "현재 테넌트의 멤버(ACTIVE·SUSPENDED 모두)" 로 좁힌다 — 정지 멤버를 제외하면
 * 재활성·제거 수단이 사라지기 때문이다. 반면 <b>자기 자신 경로</b>
 * ({@code getMyProfile}, {@code updateProfile}, {@code changePassword})는 전역 정체성이므로 좁히지
 * 않는다 — 사용자는 여러 테넌트에 속할 수 있고, 자기 이름·비밀번호는 테넌트에 딸린 속성이 아니다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final MembershipRepository membershipRepository;
  private final AuditLogService auditLogService;
  private final RefreshTokenRepository refreshTokenRepository;

  /**
   * 사용자 목록 조회. 각 사용자의 역할도 함께 내려준다(#586).
   *
   * <p>역할은 페이지에 담긴 사용자 ID들을 모아 {@link RoleRepository#findByUserIds} 로 <b>한 번에</b>
   * 배치 조회한다 — 사용자마다 {@code findByUserId}를 호출하면 페이지 크기만큼 N+1 쿼리가 발생하기
   * 때문이다(AI 에이전트의 admin-manager subagent가 목록 표시에 역할 컬럼을 요구하는데, 기존에는
   * 목록 조회 한 번으로는 역할을 채울 방법이 없어 항상 빈 컬럼으로 응답했다).
   */
  @Transactional(readOnly = true)
  public PageResponse<UserListResponse> getUsers(String search, int page, int size) {
    long tenantId = TenantContext.require("사용자 목록 조회");
    List<UserResponse> content = userRepository.findAllPaginated(tenantId, search, page, size);
    List<Long> userIds = content.stream().map(UserResponse::id).toList();
    Map<Long, List<RoleResponse>> rolesByUserId = roleRepository.findByUserIds(userIds);
    // 활성 열 = 이 워크스페이스 멤버십 상태(전역 계정 활성 아님, WD-2).
    Map<Long, TenantMembership> memberships = membershipRepository.findInTenant(tenantId, userIds);
    List<UserListResponse> withRoles =
        content.stream()
            .map(
                user -> {
                  TenantMembership m = memberships.get(user.id());
                  UserResponse shown = user.withActive(m != null && m.isActive());
                  return UserListResponse.of(
                      shown,
                      rolesByUserId.getOrDefault(user.id(), List.of()),
                      m == null ? null : m.role());
                })
            .toList();
    long totalElements = userRepository.countAll(tenantId, search);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(withRoles, page, size, totalElements, totalPages);
  }

  /**
   * 관리 경로의 사용자 상세. 현재 테넌트의 멤버가 아니면 404.
   *
   * <p><b>왜 403 이 아니라 404 인가:</b> 403 은 "그 id 의 사용자는 존재하지만 너는 볼 수 없다" 를
   * 알려 준다. 그러면 다른 테넌트의 사용자 id 를 훑어 존재 여부를 열거할 수 있다(계정 열거). 남의
   * 테넌트 사용자는 이 테넌트 입장에서 <b>없는 것</b>으로 보이는 것이 옳다.
   */
  @Transactional(readOnly = true)
  public UserDetailResponse getUserById(Long id) {
    long tenantId = TenantContext.require("사용자 상세");
    TenantMembership membership = requireTenantMember(id);
    UserDetailResponse base = loadDetail(id);
    return new UserDetailResponse(
        base.id(),
        base.username(),
        base.email(),
        base.name(),
        membership.isActive(),
        base.createdAt(),
        base.roles(),
        membership.role(),
        isLastActiveAdmin(id, membership, tenantId));
  }

  /**
   * 대상이 지금 이 테넌트의 마지막 활성 ADMIN 인가(정지·제거하면 활성 ADMIN 이 0 이 되는가).
   *
   * <p>대상이 "활성 ADMIN 집합" 에 들어 있는지를 {@code countActiveAdmins} 와 같은 조건(멤버십 ACTIVE + 전역
   * 계정 활성 + 이 테넌트 ADMIN)으로 판정한다. 전역 비활성(#784 운영자 비활성화) 대상은 이미 집합 밖이라 빼도
   * 활성 ADMIN 수가 변하지 않으므로, 이를 건너뛰지 않으면 남은 ADMIN 이 거짓 409 로 막힌다.
   */
  private boolean isLastActiveAdmin(Long userId, TenantMembership membership, long tenantId) {
    return membership.isActive()
        && userRepository.findById(userId).map(u -> u.isActive()).orElse(false)
        && userRepository.hasAdminRole(userId)
        && userRepository.countActiveAdmins(tenantId) <= 1;
  }

  /**
   * 정지·제거 공통 잠금 방지 규칙. 서버가 최종 판정한다(웹의 비활성 버튼은 안내일 뿐).
   * 순서: 자기 자신(400) → OWNER(409) → 마지막 활성 ADMIN(409).
   * 서버 가드인 이유: AI 에이전트가 자기 계정을 비활성화한 사고(#585)와 마지막 관리자 잠금(#146) 재발 방지.
   */
  private void assertRemovable(
      Long userId, Long callerId, TenantMembership membership, long tenantId) {
    if (userId.equals(callerId)) {
      throw new IllegalArgumentException("자기 자신은 정지하거나 제거할 수 없습니다");
    }
    if (membership.isOwner()) {
      throw new IllegalStateException("워크스페이스 소유자는 정지하거나 제거할 수 없습니다");
    }
    if (isLastActiveAdmin(userId, membership, tenantId)) {
      throw new IllegalStateException("이 워크스페이스의 마지막 활성 ADMIN 은 정지하거나 제거할 수 없습니다");
    }
  }

  /**
   * 자기 자신의 프로필. <b>테넌트로 좁히지 않는다</b> — 전역 정체성이다.
   *
   * <p>{@code getUserById} 와 별도 메서드인 이유: 하나로 두면 관리 경로에 테넌트 술어를 넣는 순간
   * {@code /users/me} 까지 막힌다(테넌트 미선택 상태나 멤버십 정리 중인 사용자가 자기 프로필조차
   * 못 본다). 포함되는 역할 목록은 {@code role}/{@code user_role} 이 RLS 대상이므로 자연히 현재
   * 테넌트 것만 나온다.
   */
  @Transactional(readOnly = true)
  public UserDetailResponse getMyProfile(Long userId) {
    return loadDetail(userId);
  }

  private UserDetailResponse loadDetail(Long id) {
    UserResponse user =
        userRepository
            .findById(id)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    List<RoleResponse> roles = roleRepository.findByUserId(id);
    return new UserDetailResponse(
        user.id(),
        user.username(),
        user.email(),
        user.name(),
        user.isActive(),
        user.createdAt(),
        roles);
  }

  /**
   * 대상이 현재 테넌트의 멤버(ACTIVE·SUSPENDED)가 아니면 {@link UserNotFoundException}(→ 404).
   *
   * <p>정지 멤버도 통과시키는 이유(WD-2): 정지 멤버를 404 로 만들면 재활성·제거·역할 변경이 불가능하다.
   * 존재하지 않는 사용자와 남의 테넌트 사용자가 같은 404 인 것은 그대로다(계정 열거 방지).
   */
  private TenantMembership requireTenantMember(Long userId) {
    long tenantId = TenantContext.require("사용자 관리 대상 확인");
    return membershipRepository
        .findInTenant(userId, tenantId)
        .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
  }

  @Transactional
  public void updateProfile(Long userId, String name, String email) {
    UserResponse user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

    if (email != null && !email.equals(user.email())) {
      if (userRepository.existsByEmailExcludingUser(email, userId)) {
        throw new EmailAlreadyExistsException("Email already exists: " + email);
      }
    }

    userRepository.update(userId, name, email);
  }

  /**
   * 현재 테넌트에 멤버를 추가한다(WD-2). 단일 트랜잭션.
   *
   * <ul>
   *   <li>계정 없음 → 임시 비밀번호로 계정 생성(변경 강제 표식 on) + 이 테넌트 멤버십 + 역할. 기본 테넌트
   *       자동 소속은 하지 않는다(가입 경로와 다름).
   *   <li>계정 있음 + 비멤버 → 멤버십·역할만. 비밀번호·이름 불변, temporaryPassword 무시.
   *   <li>이미 멤버(ACTIVE/SUSPENDED) → 409. 정지 멤버는 코드로 구분하고 userId 를 실어 상세 링크를 돕는다.
   * </ul>
   *
   * <p>역할 검증을 계정 생성보다 <b>먼저</b> 하는 이유: 잘못된 roleId 로 400 이 나면 트랜잭션이 롤백되긴
   * 하지만, 앞에서 끊으면 불필요한 해시 계산·쓰기 자체가 없다.
   */
  @Transactional
  public AddMemberResponse addMember(AddMemberRequest request, Long callerId) {
    long tenantId = TenantContext.require("멤버 추가");
    String email = request.email().trim().toLowerCase(Locale.ROOT);
    Set<Long> roleIds = resolveMemberRoles(request.roleIds());

    Optional<UserResponse> existing = userRepository.findByUsernameIgnoreCase(email);
    boolean created = existing.isEmpty();
    existing.ifPresent(u -> rejectIfAlreadyMember(u.id(), tenantId));
    UserResponse user;
    try {
      user = existing.orElseGet(() -> createWithTemporaryPassword(email, request));
      membershipRepository.insertMember(user.id(), tenantId);
      for (Long roleId : roleIds) {
        userRepository.addRole(user.id(), roleId);
      }
    } catch (DuplicateKeyException e) {
      // 사전 검사(rejectIfAlreadyMember) 와 INSERT 사이에 같은 요청이 동시에 들어오면 유니크 제약이 먼저 잡는다.
      // 영어 "Data integrity violation" 이 그대로 노출되지 않도록 서비스 경계에서 같은 409 로 번역한다.
      // CodedApiException(런타임) 을 던지므로 @Transactional 이 롤백한다 — 부분 생성된 계정/멤버십은 남지 않는다.
      throw new CodedApiException(
          HttpStatus.CONFLICT, "MEMBER_ALREADY_EXISTS", "이미 이 워크스페이스의 멤버입니다");
    }
    audit(
        callerId,
        "MEMBER_ADD",
        user.id(),
        created ? "새 계정을 만들어 멤버로 추가" : "기존 계정을 멤버로 추가",
        Map.of("created", created, "roleIds", List.copyOf(roleIds)));
    return new AddMemberResponse(user.id(), created);
  }

  /** 이미 이 테넌트 멤버면 409. 정지 멤버는 코드로 구분하고 userId 를 실어 웹의 상세 링크를 돕는다. */
  private void rejectIfAlreadyMember(Long userId, long tenantId) {
    Optional<TenantMembership> membership = membershipRepository.findInTenant(userId, tenantId);
    if (membership.isEmpty()) {
      return;
    }
    if (membership.get().isActive()) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "MEMBER_ALREADY_EXISTS", "이미 이 워크스페이스의 멤버입니다");
    }
    throw new CodedApiException(
        HttpStatus.CONFLICT,
        "MEMBER_SUSPENDED",
        "이미 이 워크스페이스의 멤버입니다(정지됨). 상세에서 재활성화하세요",
        Map.of("userId", String.valueOf(userId)));
  }

  /** 계정이 없을 때 임시 비밀번호(변경 강제 표식 on)로 새 계정을 만든다. username = 소문자 이메일. */
  private UserResponse createWithTemporaryPassword(String email, AddMemberRequest request) {
    // username 은 없는데 다른 계정이 이 이메일을 쓰고 있으면 같은 사람의 두 번째 계정이 된다 — 거부.
    // 대소문자 무시: 과거 계정의 이메일이 대소문자 섞여 저장돼 있어도 같은 사람으로 본다(리뷰 지적 4).
    if (userRepository.existsByEmailIgnoreCase(email)) {
      throw new EmailAlreadyExistsException("이미 사용 중인 이메일입니다.");
    }
    return userRepository.saveWithTemporaryPassword(
        email, email, passwordEncoder.encode(request.temporaryPassword()), request.name().trim());
  }

  /**
   * 요청 역할을 검증하고 USER 를 합친다.
   *
   * <p>USER 를 항상 넣는 이유: USER 는 워크스페이스 기본 역할이다. 초대된 멤버가 권한을 하나도 갖지 않는
   * 상태를 만들지 않는다(사용자 결정, 계획 "판단 사항 1"). 선택 역할은 USER 에 추가된다.
   */
  private Set<Long> resolveMemberRoles(List<Long> requested) {
    Set<Long> ids = new LinkedHashSet<>(requested == null ? List.of() : requested);
    Set<Long> visible = roleRepository.findExistingIds(ids);
    if (!visible.containsAll(ids)) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST, "INVALID_ROLE", "이 워크스페이스에 없는 역할이 포함되어 있습니다");
    }
    Long userRoleId =
        roleRepository
            .findByName("USER")
            .orElseThrow(() -> new RoleNotFoundException("System role not found: USER"))
            .id();
    ids.add(userRoleId);
    return ids;
  }

  /**
   * 멤버 관리 감사 로그. 행위자 username 은 감사 테이블 NOT NULL 이라 조회해서 넣는다.
   * audit_log.tenant_id 는 GUC 기본값 — 호출 트랜잭션의 현재 테넌트로 기록된다.
   */
  private void audit(
      Long callerId, String action, Long targetUserId, String description, Object metadata) {
    String callerName =
        userRepository.findById(callerId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        callerId,
        callerName,
        action,
        "user",
        String.valueOf(targetUserId),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }

  /**
   * 내 비밀번호 변경. 성공하면 이 사용자의 <b>모든</b> refresh 토큰 패밀리를 폐기한다.
   *
   * <p>왜 전부인가(WD-2 리뷰 지적 2): 임시 비밀번호를 아는 다른 사람(예: 발급한 관리자)이 먼저 로그인해 둔
   * 세션은 변경 뒤에도 refresh 로 표식 없는 토큰을 받아 계속 쓸 수 있었다. 호출자의 패밀리만 남기고 싶어도
   * 이 엔드포인트에서는 알 수 없다 — refresh 쿠키는 {@code Path=/api/v1/auth} 라 여기로 오지 않고 access
   * token 에는 패밀리 클레임이 없다. 그래서 전부 폐기하고, 호출자 세션은 컨트롤러가 새 패밀리를 발급해
   * 쿠키로 이어 준다({@code AuthService#startSessionAfterPasswordChange}). 폐기를 같은 트랜잭션에 두어
   * "비밀번호는 바뀌었는데 옛 세션은 살아 있는" 중간 상태가 커밋되지 않게 한다.
   */
  @Transactional
  public void changePassword(Long userId, String currentPassword, String newPassword) {
    String storedPassword =
        userRepository
            .findPasswordById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

    // 현재 비밀번호 불일치 시 400 Bad Request로 명확한 한국어 메시지 반환 (#27)
    if (!passwordEncoder.matches(currentPassword, storedPassword)) {
      throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다");
    }
    // 같은 값으로 "변경" 하면 임시 비밀번호가 그대로인데 변경 강제 표식만 꺼진다 — 웹 스키마만 막으면 API
    // 직접 호출로 강제 변경을 우회할 수 있으므로 서버가 최종 판정한다(리뷰 지적 1). 같은 400 스타일(#27).
    if (passwordEncoder.matches(newPassword, storedPassword)) {
      throw new IllegalArgumentException("새 비밀번호는 현재 비밀번호와 달라야 합니다");
    }

    userRepository.updatePassword(userId, passwordEncoder.encode(newPassword));
    refreshTokenRepository.revokeAllByUserId(userId);
  }

  /**
   * 사용자 역할 전체 교체.
   *
   * <p>잠금 방지 두 가지(서버가 최종 판정):
   *
   * <ol>
   *   <li>자기 자신의 ADMIN 제거 금지(#57) — 400.
   *   <li>이 테넌트의 마지막 활성 ADMIN 에게서 ADMIN 제거 금지(#785) — 409. 정지·제거의 {@link
   *       #assertRemovable} 과 같은 규칙({@link #isLastActiveAdmin})이다. 역할 회수도 "활성 ADMIN 수를 줄이는"
   *       변경이기 때문이다.
   * </ol>
   *
   * <p>잠금을 멤버십 조회·판정보다 먼저 잡는 이유: 두 관리자가 서로의 ADMIN 을 동시에 빼거나, 한쪽은 정지·한쪽은
   * 역할 회수를 겹치면, 잠금 없이는 둘 다 "활성 ADMIN 2명" 을 보고 통과해 0 명이 된다. setUserActive·removeMember
   * 와 <b>같은</b> 테넌트 잠금이라 세 경로가 서로 직렬화된다.
   *
   * <p>roleIds 가 null 이면 빈 목록(전체 제거)으로 본다 — 예전에는 setRoles 에서 NPE(500)가 났다.
   */
  @Transactional
  public void setUserRoles(Long userId, List<Long> roleIds, Long callerId) {
    long tenantId = TenantContext.require("역할 변경");
    userRepository.acquireMemberAdminGuardLock(tenantId);
    // 남의 테넌트 사용자에게 역할을 부여/회수할 수 없다. 존재 확인을 멤버십 확인으로 대체한다.
    TenantMembership membership = requireTenantMember(userId);
    List<Long> requested = roleIds == null ? List.of() : roleIds;

    // 이번 변경이 대상의 ADMIN 을 빼는가 — 지금 ADMIN 이고, 요청 목록에 이 테넌트 ADMIN id 가 없을 때.
    Long adminRoleId = roleRepository.findByName("ADMIN").map(RoleResponse::id).orElse(null);
    boolean removesAdmin =
        adminRoleId != null
            && !requested.contains(adminRoleId)
            && userRepository.hasAdminRole(userId);
    if (removesAdmin && userId.equals(callerId)) {
      // 자기 잠금(self-lockout) 방지 (#57) — 기존 400 계약 유지
      throw new IllegalArgumentException("자신의 ADMIN 역할은 제거할 수 없습니다");
    }
    if (removesAdmin && isLastActiveAdmin(userId, membership, tenantId)) {
      throw new IllegalStateException("이 워크스페이스의 마지막 활성 ADMIN 에게서 ADMIN 역할을 뺄 수 없습니다");
    }
    userRepository.setRoles(userId, requested);
  }

  /**
   * 이 워크스페이스에서의 멤버십 정지/재활성(WD-2). 전역 계정({@code user.is_active})은 건드리지 않는다
   * — 한 테넌트 관리자가 다중 소속 사용자의 다른 테넌트 로그인까지 막던 결함의 수정이다.
   */
  @Transactional
  public void setUserActive(Long userId, boolean active, Long callerId) {
    long tenantId = TenantContext.require("멤버십 정지/재활성");
    // 멤버십 조회·마지막 ADMIN 판정보다 먼저 잠근다 — 동시 상호 정지로 활성 ADMIN 0 방지(리뷰 지적 3).
    userRepository.acquireMemberAdminGuardLock(tenantId);
    TenantMembership membership = requireTenantMember(userId);
    if (active == membership.isActive()) {
      return; // 이미 원하는 상태 — 감사 로그도 남기지 않는다
    }
    if (!active) {
      assertRemovable(userId, callerId, membership, tenantId);
    }
    membershipRepository.updateStatus(userId, tenantId, active ? "ACTIVE" : "SUSPENDED");
    audit(
        callerId,
        active ? "MEMBER_REACTIVATE" : "MEMBER_SUSPEND",
        userId,
        active ? "멤버십 재활성" : "멤버십 정지",
        null);
  }

  /** 이 워크스페이스에서 제거: 이 테넌트의 user_role + membership 만 삭제. 계정·만든 리소스는 유지. */
  @Transactional
  public void removeMember(Long userId, Long callerId) {
    long tenantId = TenantContext.require("멤버 제거");
    // setUserActive 와 같은 잠금 — 정지와 제거가 섞여 겹쳐도 직렬화된다(리뷰 지적 3).
    userRepository.acquireMemberAdminGuardLock(tenantId);
    TenantMembership membership = requireTenantMember(userId);
    assertRemovable(userId, callerId, membership, tenantId);
    userRepository.deleteRolesInTenant(userId, tenantId);
    membershipRepository.delete(userId, tenantId);
    audit(callerId, "MEMBER_REMOVE", userId, "워크스페이스에서 제거", null);
  }
}

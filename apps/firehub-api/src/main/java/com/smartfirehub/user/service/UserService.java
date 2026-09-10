package com.smartfirehub.user.service;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserDetailResponse;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 관리 서비스.
 *
 * <p><b>테넌트 경계에 대하여.</b> {@code "user"} 는 전역 테이블(tenant_id 없음, RLS 없음)이라 다른
 * 도메인처럼 RLS 가 알아서 격리해 주지 않는다. 그래서 <b>관리 경로</b>(목록·상세·역할부여·활성화)는
 * 여기서 명시적으로 "현재 테넌트의 ACTIVE 멤버" 로 좁힌다. 반면 <b>자기 자신 경로</b>
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

  @Transactional(readOnly = true)
  public PageResponse<UserResponse> getUsers(String search, int page, int size) {
    long tenantId = TenantContext.require("사용자 목록 조회");
    List<UserResponse> content = userRepository.findAllPaginated(tenantId, search, page, size);
    long totalElements = userRepository.countAll(tenantId, search);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
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
    requireTenantMember(id);
    return loadDetail(id);
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
   * 대상 사용자가 현재 테넌트의 ACTIVE 멤버가 아니면 {@link UserNotFoundException}(→ 404).
   *
   * <p>존재하지 않는 사용자와 남의 테넌트 사용자가 <b>같은 응답</b>이 되도록 일부러 하나의 검사로
   * 합쳤다. 두 경우를 다르게 응답하면 그 차이가 곧 계정 열거 채널이 된다.
   */
  private void requireTenantMember(Long userId) {
    long tenantId = TenantContext.require("사용자 관리 대상 확인");
    if (!membershipRepository.hasActiveMembership(userId, tenantId)) {
      throw new UserNotFoundException("User not found: " + userId);
    }
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

    userRepository.updatePassword(userId, passwordEncoder.encode(newPassword));
  }

  @Transactional
  public void setUserRoles(Long userId, List<Long> roleIds, Long callerId) {
    // 남의 테넌트 사용자에게 역할을 부여/회수할 수 없다. 존재 확인을 멤버십 확인으로 대체한다.
    requireTenantMember(userId);
    // 자기 자신의 ADMIN 역할 제거 차단 — 자기 잠금(self-lockout) 방지 (#57)
    if (userId.equals(callerId)) {
      roleRepository
          .findByName("ADMIN")
          .ifPresent(
              adminRole -> {
                List<RoleResponse> currentRoles = roleRepository.findByUserId(userId);
                boolean hasAdminNow =
                    currentRoles.stream().anyMatch(r -> r.id().equals(adminRole.id()));
                boolean wouldRemoveAdmin = roleIds == null || !roleIds.contains(adminRole.id());
                if (hasAdminNow && wouldRemoveAdmin) {
                  throw new IllegalArgumentException("자신의 ADMIN 역할은 제거할 수 없습니다");
                }
              });
    }
    userRepository.setRoles(userId, roleIds);
  }

  @Transactional
  public void setUserActive(Long userId, boolean active, Long callerId) {
    // 남의 테넌트 사용자를 비활성화(계정 잠금)할 수 없다. 존재 확인을 멤버십 확인으로 대체한다.
    requireTenantMember(userId);
    // 자기 자신 비활성화 차단 — 즉시 로그인 불가 자기잠금(self-lockout) 방지 (#585).
    // AI 에이전트(admin-manager subagent)가 "자기 자신 비활성화 금지" 규칙을 프롬프트
    // 레벨에서 놓치더라도(사용자가 자기 자신임을 밝혔는데도 확인 절차만 거쳐 실행한 사고 사례),
    // 실제 인증된 호출자와 대상 userId 를 서버가 직접 비교해 파괴적 액션을 원천 차단한다
    // (defense-in-depth — 모델 판단에만 의존하지 않음).
    if (!active && userId.equals(callerId)) {
      throw new IllegalArgumentException("자기 자신의 계정은 비활성화할 수 없습니다");
    }
    // 마지막 활성 ADMIN 비활성화 방지 — 모든 ADMIN이 잠기면 시스템 관리 불가 (#146)
    if (!active && userRepository.hasAdminRole(userId) && userRepository.countActiveAdmins() <= 1) {
      throw new IllegalStateException("마지막 활성 ADMIN 계정은 비활성화할 수 없습니다");
    }
    userRepository.setActive(userId, active);
  }
}

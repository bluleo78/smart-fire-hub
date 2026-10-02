package com.smartfirehub.user.controller;

import com.smartfirehub.auth.controller.RefreshTokenCookies;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.AllowedDuringPasswordChange;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.user.dto.*;
import com.smartfirehub.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;
  private final AuthService authService;
  private final RefreshTokenCookies refreshTokenCookies;

  /**
   * 자기 프로필 조회. 전역 정체성 경로이므로 테넌트 멤버십으로 좁히지 않는다 — 관리 경로인
   * {@code GET /{id}} 와 달리 {@code getMyProfile} 을 쓰는 이유다.
   */
  @GetMapping("/me")
  public ResponseEntity<UserDetailResponse> getMyProfile(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    UserDetailResponse user = userService.getMyProfile(userId);
    return ResponseEntity.ok(user);
  }

  /**
   * 내 프로필 수정. <b>인증만 요구한다</b>(권한 코드 없음, WD-2).
   *
   * <p>항상 본인(principal) 대상이고 {@code "user"} 는 전역 테이블이라 테넌트 RBAC 와 무관하다. 권한으로
   * 막으면 테넌트 미선택 토큰(멤버십 여러 개인 첫 로그인)에서 RLS 로 권한이 0 이 되어 본인 계정조차 못
   * 고친다. {@code user:write:self} 권한 코드는 카탈로그(permission 테이블)에 남아 있지만 이 엔드포인트의
   * 게이트로는 쓰지 않는다.
   */
  @PutMapping("/me")
  public ResponseEntity<Void> updateMyProfile(
      Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    userService.updateProfile(userId, request.name(), request.email());
    return ResponseEntity.noContent().build();
  }

  /**
   * 내 비밀번호 변경. 위와 같은 이유로 인증만 요구한다({@code user:write:self} 로 막지 않는다). 비밀번호
   * 변경 강제 중에도 허용된다 — 이 핸들러가 강제 상태를 푸는 유일한 수단이다.
   *
   * <p>변경이 이 사용자의 refresh 세션을 전부 폐기하므로(리뷰 지적 2), 호출자에게는 새 패밀리의 refresh
   * 쿠키를 내려 세션을 잇는다. 응답 본문은 그대로 204 — 웹은 이어서 {@code /auth/refresh} 로 access token 을
   * 받는다. 새 세션 발급이 실패해도 비밀번호 변경·폐기는 이미 커밋돼 안전하다(웹은 재로그인으로 안내).
   */
  @PutMapping("/me/password")
  @AllowedDuringPasswordChange
  public ResponseEntity<Void> changeMyPassword(
      Authentication authentication,
      @Valid @RequestBody ChangePasswordRequest request,
      HttpServletResponse response) {
    Long userId = (Long) authentication.getPrincipal();
    userService.changePassword(userId, request.currentPassword(), request.newPassword());
    // 테넌트 미선택 토큰이면 null — require 를 쓰지 않는다(본인 계정 경로는 테넌트 없이도 돼야 한다).
    var session = authService.startSessionAfterPasswordChange(userId, TenantContext.get());
    refreshTokenCookies.set(response, session.refreshToken());
    return ResponseEntity.noContent().build();
  }

  @GetMapping
  @RequirePermission("user:read")
  public ResponseEntity<PageResponse<UserListResponse>> getUsers(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PageResponse<UserListResponse> users = userService.getUsers(search, page, size);
    return ResponseEntity.ok(users);
  }

  /**
   * 멤버 추가(WD-2). user:write 필수, 역할을 지정하면 role:assign 도 필요하다.
   *
   * <p>role:assign 을 애너테이션이 아니라 여기서 보는 이유: {@code @RequirePermission} 은 AND 고정이라
   * "roleIds 가 있을 때만" 같은 조건부 요구를 표현할 수 없다. 메시지는 PermissionInterceptor 와 같은
   * 형식으로 맞춘다.
   */
  @PostMapping
  @RequirePermission("user:write")
  public ResponseEntity<AddMemberResponse> addMember(
      Authentication authentication, @Valid @RequestBody AddMemberRequest request) {
    boolean assignsRoles = request.roleIds() != null && !request.roleIds().isEmpty();
    boolean canAssign =
        authentication.getAuthorities().stream()
            .anyMatch(a -> "role:assign".equals(a.getAuthority()));
    if (assignsRoles && !canAssign) {
      throw new AccessDeniedException("Missing required permission: role:assign");
    }
    Long callerId = (Long) authentication.getPrincipal();
    return ResponseEntity.status(HttpStatus.CREATED).body(userService.addMember(request, callerId));
  }

  @GetMapping("/{id}")
  @RequirePermission("user:read")
  public ResponseEntity<UserDetailResponse> getUserById(@PathVariable Long id) {
    UserDetailResponse user = userService.getUserById(id);
    return ResponseEntity.ok(user);
  }

  @PutMapping("/{id}/roles")
  @RequirePermission("role:assign")
  public ResponseEntity<Void> setUserRoles(
      Authentication authentication,
      @PathVariable Long id,
      @Valid @RequestBody SetRolesRequest request) {
    Long callerId = (Long) authentication.getPrincipal();
    userService.setUserRoles(id, request.roleIds(), callerId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/active")
  @RequirePermission("user:write")
  public ResponseEntity<Void> setUserActive(
      Authentication authentication,
      @PathVariable Long id,
      @Valid @RequestBody SetActiveRequest request) {
    Long callerId = (Long) authentication.getPrincipal();
    userService.setUserActive(id, request.active(), callerId);
    return ResponseEntity.noContent().build();
  }

  /** 이 워크스페이스에서 제거(WD-2). 계정과 그 사용자가 만든 리소스는 남는다. */
  @DeleteMapping("/{id}/membership")
  @RequirePermission("user:write")
  public ResponseEntity<Void> removeMember(Authentication authentication, @PathVariable Long id) {
    Long callerId = (Long) authentication.getPrincipal();
    userService.removeMember(id, callerId);
    return ResponseEntity.noContent().build();
  }
}

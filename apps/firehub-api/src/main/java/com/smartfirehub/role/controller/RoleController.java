package com.smartfirehub.role.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.role.dto.*;
import com.smartfirehub.role.service.RoleService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 역할 관리 API. 변경 엔드포인트는 인증 주체(principal = 사용자 id)를 행위자로 서비스에 넘긴다 — 역할·권한 변경 감사 로그(WD-117)에 누가 바꿨는지
 * 남기기 위해서다(UserController 의 callerId 와 같은 방식).
 */
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

  private final RoleService roleService;

  @GetMapping
  @RequirePermission("role:read")
  public ResponseEntity<List<RoleResponse>> getAllRoles() {
    List<RoleResponse> roles = roleService.getAllRoles();
    return ResponseEntity.ok(roles);
  }

  /**
   * 호출자가 부여할 수 없는 역할 목록(WD-291) — 범위 안 역할은 빠진다. 부여 권한(role:assign)이 있어야 의미가 있으므로 그 권한으로 연다. 화면은 이
   * 목록으로 체크박스를 잠그고, 실제 부여 때의 403 과 같은 문구를 보여 준다.
   */
  @GetMapping("/grant-blocked")
  @RequirePermission("role:assign")
  public ResponseEntity<List<RoleGrantBlockResponse>> getGrantBlockedRoles(
      Authentication authentication) {
    Long callerId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(roleService.getGrantBlockedRoles(callerId));
  }

  @GetMapping("/{id}")
  @RequirePermission("role:read")
  public ResponseEntity<RoleDetailResponse> getRoleById(@PathVariable Long id) {
    RoleDetailResponse role = roleService.getRoleById(id);
    return ResponseEntity.ok(role);
  }

  @PostMapping
  @RequirePermission("role:write")
  public ResponseEntity<RoleResponse> createRole(
      Authentication authentication, @Valid @RequestBody CreateRoleRequest request) {
    Long callerId = (Long) authentication.getPrincipal();
    RoleResponse role = roleService.createRole(request.name(), request.description(), callerId);
    return ResponseEntity.status(HttpStatus.CREATED).body(role);
  }

  @PutMapping("/{id}")
  @RequirePermission("role:write")
  public ResponseEntity<Void> updateRole(
      Authentication authentication,
      @PathVariable Long id,
      @Valid @RequestBody UpdateRoleRequest request) {
    Long callerId = (Long) authentication.getPrincipal();
    roleService.updateRole(id, request.name(), request.description(), callerId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @RequirePermission("role:delete")
  public ResponseEntity<Void> deleteRole(Authentication authentication, @PathVariable Long id) {
    Long callerId = (Long) authentication.getPrincipal();
    roleService.deleteRole(id, callerId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/permissions")
  @RequirePermission("role:write")
  public ResponseEntity<Void> setRolePermissions(
      Authentication authentication,
      @PathVariable Long id,
      @Valid @RequestBody SetPermissionsRequest request) {
    Long callerId = (Long) authentication.getPrincipal();
    roleService.setRolePermissions(
        id, request.permissionIds(), request.expectedPermissionIds(), callerId);
    return ResponseEntity.noContent().build();
  }
}

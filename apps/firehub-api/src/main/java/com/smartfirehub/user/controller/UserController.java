package com.smartfirehub.user.controller;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.user.dto.*;
import com.smartfirehub.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserDetailResponse> getMyProfile(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        UserDetailResponse user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/me")
    @RequirePermission("user:write:self")
    public ResponseEntity<Void> updateMyProfile(Authentication authentication,
                                                 @Valid @RequestBody UpdateProfileRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        userService.updateProfile(userId, request.name(), request.email());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/password")
    @RequirePermission("user:write:self")
    public ResponseEntity<Void> changeMyPassword(Authentication authentication,
                                                  @Valid @RequestBody ChangePasswordRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        userService.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @RequirePermission("user:read")
    public ResponseEntity<PageResponse<UserResponse>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<UserResponse> users = userService.getUsers(search, page, size);
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    @RequirePermission("user:read")
    public ResponseEntity<UserDetailResponse> getUserById(@PathVariable Long id) {
        UserDetailResponse user = userService.getUserById(id);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/{id}/roles")
    @RequirePermission("role:assign")
    public ResponseEntity<Void> setUserRoles(@PathVariable Long id,
                                              @Valid @RequestBody SetRolesRequest request) {
        userService.setUserRoles(id, request.roleIds());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/active")
    @RequirePermission("user:write")
    public ResponseEntity<Void> setUserActive(@PathVariable Long id,
                                               @Valid @RequestBody SetActiveRequest request) {
        userService.setUserActive(id, request.active());
        return ResponseEntity.noContent().build();
    }
}

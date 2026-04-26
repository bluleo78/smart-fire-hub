package com.smartfirehub.user.service;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.user.dto.UserDetailResponse;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional(readOnly = true)
  public PageResponse<UserResponse> getUsers(String search, int page, int size) {
    List<UserResponse> content = userRepository.findAllPaginated(search, page, size);
    long totalElements = userRepository.countAll(search);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }

  @Transactional(readOnly = true)
  public UserDetailResponse getUserById(Long id) {
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
    if (!userRepository.existsById(userId)) {
      throw new UserNotFoundException("User not found: " + userId);
    }
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
  public void setUserActive(Long userId, boolean active) {
    if (!userRepository.existsById(userId)) {
      throw new UserNotFoundException("User not found: " + userId);
    }
    userRepository.setActive(userId, active);
  }
}

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;

  public UserService(
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
  }

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

    if (!passwordEncoder.matches(currentPassword, storedPassword)) {
      throw new IllegalArgumentException("Current password is incorrect");
    }

    userRepository.updatePassword(userId, passwordEncoder.encode(newPassword));
  }

  @Transactional
  public void setUserRoles(Long userId, List<Long> roleIds) {
    if (!userRepository.existsById(userId)) {
      throw new UserNotFoundException("User not found: " + userId);
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

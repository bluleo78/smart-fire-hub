package com.smartfirehub.user.service;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.user.dto.UserDetailResponse;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    private UserResponse testUser;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, roleRepository, passwordEncoder);
        testUser = new UserResponse(1L, "testuser", "test@example.com", "Test User", true, LocalDateTime.now());
    }

    @Test
    void getUsers_returnsPaginatedResults() {
        List<UserResponse> users = List.of(testUser);
        when(userRepository.findAllPaginated("test", 0, 20)).thenReturn(users);
        when(userRepository.countAll("test")).thenReturn(1L);

        PageResponse<UserResponse> result = userService.getUsers("test", 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).username()).isEqualTo("testuser");
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void getUserById_returnsUserWithRoles() {
        List<RoleResponse> roles = List.of(new RoleResponse(1L, "ADMIN", "Administrator", true));
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(roleRepository.findByUserId(1L)).thenReturn(roles);

        UserDetailResponse result = userService.getUserById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.username()).isEqualTo("testuser");
        assertThat(result.roles()).hasSize(1);
        assertThat(result.roles().get(0).name()).isEqualTo("ADMIN");
    }

    @Test
    void getUserById_notFound_throwsException() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateProfile_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.existsByEmailExcludingUser("new@example.com", 1L)).thenReturn(false);

        userService.updateProfile(1L, "New Name", "new@example.com");

        verify(userRepository).update(1L, "New Name", "new@example.com");
    }

    @Test
    void updateProfile_emailConflict_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.existsByEmailExcludingUser("taken@example.com", 1L)).thenReturn(true);

        assertThatThrownBy(() -> userService.updateProfile(1L, "New Name", "taken@example.com"))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void changePassword_success() {
        when(userRepository.findPasswordById(1L)).thenReturn(Optional.of("encoded-old"));
        when(passwordEncoder.matches("oldpass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("newpass")).thenReturn("encoded-new");

        userService.changePassword(1L, "oldpass", "newpass");

        verify(userRepository).updatePassword(1L, "encoded-new");
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsException() {
        when(userRepository.findPasswordById(1L)).thenReturn(Optional.of("encoded-old"));
        when(passwordEncoder.matches("wrongpass", "encoded-old")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(1L, "wrongpass", "newpass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Current password is incorrect");
    }

    @Test
    void setUserRoles_success() {
        when(userRepository.existsById(1L)).thenReturn(true);

        userService.setUserRoles(1L, List.of(1L, 2L));

        verify(userRepository).setRoles(1L, List.of(1L, 2L));
    }

    @Test
    void setUserActive_success() {
        when(userRepository.existsById(1L)).thenReturn(true);

        userService.setUserActive(1L, false);

        verify(userRepository).setActive(1L, false);
    }
}

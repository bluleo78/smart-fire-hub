package com.smartfirehub.role.service;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.role.dto.RoleDetailResponse;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.role.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    private RoleService roleService;

    @BeforeEach
    void setUp() {
        roleService = new RoleService(roleRepository, permissionRepository);
    }

    @Test
    void getAllRoles_returnsList() {
        List<RoleResponse> roles = List.of(
                new RoleResponse(1L, "ADMIN", "Administrator", true),
                new RoleResponse(2L, "USER", "Regular user", true)
        );
        when(roleRepository.findAll()).thenReturn(roles);

        List<RoleResponse> result = roleService.getAllRoles();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("ADMIN");
    }

    @Test
    void getRoleById_returnsRoleWithPermissions() {
        RoleResponse role = new RoleResponse(1L, "ADMIN", "Administrator", true);
        List<PermissionResponse> permissions = List.of(
                new PermissionResponse(1L, "user:read", "Read users", "user"),
                new PermissionResponse(2L, "user:write", "Write users", "user")
        );
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(permissionRepository.findByRoleId(1L)).thenReturn(permissions);

        RoleDetailResponse result = roleService.getRoleById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("ADMIN");
        assertThat(result.permissions()).hasSize(2);
    }

    @Test
    void getRoleById_notFound_throwsException() {
        when(roleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.getRoleById(999L))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void createRole_success() {
        RoleResponse created = new RoleResponse(3L, "MODERATOR", "Moderator role", false);
        when(roleRepository.existsByName("MODERATOR")).thenReturn(false);
        when(roleRepository.save("MODERATOR", "Moderator role")).thenReturn(created);

        RoleResponse result = roleService.createRole("MODERATOR", "Moderator role");

        assertThat(result.id()).isEqualTo(3L);
        assertThat(result.name()).isEqualTo("MODERATOR");
    }

    @Test
    void createRole_duplicateName_throwsException() {
        when(roleRepository.existsByName("ADMIN")).thenReturn(true);

        assertThatThrownBy(() -> roleService.createRole("ADMIN", "Duplicate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateRole_success() {
        RoleResponse role = new RoleResponse(3L, "MODERATOR", "Moderator", false);
        when(roleRepository.findById(3L)).thenReturn(Optional.of(role));

        roleService.updateRole(3L, "MOD", "Updated moderator");

        verify(roleRepository).update(3L, "MOD", "Updated moderator");
    }

    @Test
    void updateRole_systemRole_throwsException() {
        RoleResponse systemRole = new RoleResponse(1L, "ADMIN", "Administrator", true);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(systemRole));

        assertThatThrownBy(() -> roleService.updateRole(1L, "RENAMED", "Try rename"))
                .isInstanceOf(SystemRoleModificationException.class);
    }

    @Test
    void deleteRole_success() {
        RoleResponse role = new RoleResponse(3L, "MODERATOR", "Moderator", false);
        when(roleRepository.findById(3L)).thenReturn(Optional.of(role));

        roleService.deleteRole(3L);

        verify(roleRepository).deleteById(3L);
    }

    @Test
    void deleteRole_systemRole_throwsException() {
        RoleResponse systemRole = new RoleResponse(1L, "ADMIN", "Administrator", true);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(systemRole));

        assertThatThrownBy(() -> roleService.deleteRole(1L))
                .isInstanceOf(SystemRoleModificationException.class);
    }

    @Test
    void setRolePermissions_success() {
        RoleResponse role = new RoleResponse(1L, "ADMIN", "Administrator", true);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));

        roleService.setRolePermissions(1L, List.of(1L, 2L, 3L));

        verify(roleRepository).setPermissions(1L, List.of(1L, 2L, 3L));
    }
}

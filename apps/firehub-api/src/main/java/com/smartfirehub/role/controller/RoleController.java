package com.smartfirehub.role.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.role.dto.*;
import com.smartfirehub.role.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @RequirePermission("role:read")
    public ResponseEntity<List<RoleResponse>> getAllRoles() {
        List<RoleResponse> roles = roleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    @GetMapping("/{id}")
    @RequirePermission("role:read")
    public ResponseEntity<RoleDetailResponse> getRoleById(@PathVariable Long id) {
        RoleDetailResponse role = roleService.getRoleById(id);
        return ResponseEntity.ok(role);
    }

    @PostMapping
    @RequirePermission("role:write")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleResponse role = roleService.createRole(request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(role);
    }

    @PutMapping("/{id}")
    @RequirePermission("role:write")
    public ResponseEntity<Void> updateRole(@PathVariable Long id,
                                            @Valid @RequestBody UpdateRoleRequest request) {
        roleService.updateRole(id, request.name(), request.description());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("role:delete")
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/permissions")
    @RequirePermission("role:write")
    public ResponseEntity<Void> setRolePermissions(@PathVariable Long id,
                                                    @Valid @RequestBody SetPermissionsRequest request) {
        roleService.setRolePermissions(id, request.permissionIds());
        return ResponseEntity.noContent().build();
    }
}

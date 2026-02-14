package com.smartfirehub.permission.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    @RequirePermission("permission:read")
    public ResponseEntity<List<PermissionResponse>> getPermissions(
            @RequestParam(required = false) String category) {
        List<PermissionResponse> permissions;
        if (category != null && !category.isBlank()) {
            permissions = permissionService.getPermissionsByCategory(category);
        } else {
            permissions = permissionService.getAllPermissions();
        }
        return ResponseEntity.ok(permissions);
    }
}

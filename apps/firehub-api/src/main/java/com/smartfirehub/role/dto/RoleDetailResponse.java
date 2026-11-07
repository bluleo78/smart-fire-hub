package com.smartfirehub.role.dto;

import com.smartfirehub.permission.dto.PermissionResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 역할 상세 응답.
 *
 * @param updatedAt 이름·설명을 마지막으로 바꾼 시각(WD-575) — 웹이 이름·설명 저장의 편집 기준 버전({@code expectedUpdatedAt})으로
 *     그대로 돌려보낸다. 권한·열람 등급 변경은 이 값을 바꾸지 않는다(권한은 {@code expectedPermissionIds} 로 따로 보호, WD-440).
 */
public record RoleDetailResponse(
    Long id,
    String name,
    String description,
    boolean isSystem,
    List<PermissionResponse> permissions,
    LocalDateTime updatedAt) {}

package com.smartfirehub.role.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 역할 권한 교체 요청.
 *
 * @param permissionIds 저장할 권한 목록(전체 교체)
 * @param expectedPermissionIds 화면이 편집을 시작할 때 읽은 권한 목록(WD-440). 서버의 지금 권한과 다르면 409 EDIT_CONFLICT 로
 *     거부한다 — 그사이 회수한 권한이 옛 화면의 저장으로 경고 없이 되살아나지 않게. null 이면 비교하지 않는다(기준 없이 부르는 호출부·「내 변경으로 덮어쓰기」).
 */
public record SetPermissionsRequest(
    @NotNull List<Long> permissionIds, List<Long> expectedPermissionIds) {}

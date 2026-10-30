package com.smartfirehub.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 사용자 역할 교체 요청.
 *
 * @param roleIds 저장할 역할 목록(전체 교체)
 * @param expectedRoleIds 화면이 편집을 시작할 때 읽은 역할 목록(WD-440). 서버의 지금 역할과 다르면 409 EDIT_CONFLICT 로 거부한다 —
 *     그사이 다른 관리자가 회수한 ADMIN 이 옛 화면의 저장으로 경고 없이 되살아나지 않게. null 이면 비교하지 않는다(기준 없이 부르는 호출부·「내 변경으로
 *     덮어쓰기」).
 */
public record SetRolesRequest(@NotNull List<Long> roleIds, List<Long> expectedRoleIds) {}

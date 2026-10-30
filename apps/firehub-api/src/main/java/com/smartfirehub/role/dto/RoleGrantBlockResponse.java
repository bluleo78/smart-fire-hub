package com.smartfirehub.role.dto;

/**
 * 호출자가 부여할 수 없는 역할 하나(WD-291). {@code code}·{@code message} 는 그 역할을 실제로 부여하려 할 때 받을 403 과 같다 — 화면이
 * 잠근 까닭을 서버 문구 그대로 보여 주게 한다.
 */
public record RoleGrantBlockResponse(long roleId, String code, String message) {}

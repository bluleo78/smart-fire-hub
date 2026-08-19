package com.smartfirehub.settings.dto;

import java.time.LocalDateTime;

/**
 * 해석된 설정 값 하나(Task 4). web 이 목록 화면에서 "지금 나가고 있는 값"뿐 아니라 "이 값이
 * 테넌트 오버라이드에서 온 것인지"와 "이 키를 테넌트가 바꿀 수 있는지"를 함께 보여주기 위한
 * 응답이다 — {@link SettingResponse} 는 이 두 정보를 담지 못한다.
 *
 * @param value {@code tenant_settings} 오버라이드가 있으면 그 값, 없으면 {@code system_settings} 값
 * @param overridden 현재 테넌트 컨텍스트에서 오버라이드가 실제로 적용됐는지
 * @param tenantEditable 이 키 자체가 화이트리스트({@code SettingsOverridePolicy})에 있어 테넌트가
 *     편집 가능한 키인지 — 현재 오버라이드 존재 여부와 무관하게 키 고유의 성질이다
 */
public record ResolvedSettingResponse(
    String key,
    String value,
    String description,
    LocalDateTime updatedAt,
    boolean overridden,
    boolean tenantEditable) {}

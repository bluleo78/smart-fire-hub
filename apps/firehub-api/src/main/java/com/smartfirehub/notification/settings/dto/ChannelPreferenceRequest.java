package com.smartfirehub.notification.settings.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 채널 알림 수신 여부 변경 요청 DTO.
 *
 * <p>enabled 는 래퍼 {@link Boolean} + {@code @NotNull} 이다(WD-595). 원시 boolean 이면 본문에서 빠뜨리거나 이름을 잘못 쓴
 * 요청({@code {}}, {@code {"enabledd":true}})이 false 로 바인딩되어, 400 없이 204 와 함께 켜 둔 채널이 조용히 「수신 끔」으로
 * 저장됐다. 컨트롤러의 {@code @Valid} 와 함께 누락을 400 으로 막아 기존 설정을 지킨다.
 *
 * @param enabled 활성화 여부(필수)
 */
public record ChannelPreferenceRequest(
    @NotNull(message = "알림 수신 여부(enabled)는 필수입니다.") Boolean enabled) {}

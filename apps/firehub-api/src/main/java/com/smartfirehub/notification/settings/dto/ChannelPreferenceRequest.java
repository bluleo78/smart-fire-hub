package com.smartfirehub.notification.settings.dto;

/**
 * 채널 알림 수신 여부 변경 요청 DTO.
 *
 * @param enabled 활성화 여부
 */
public record ChannelPreferenceRequest(boolean enabled) {}

package com.smartfirehub.notification.settings.dto;

/**
 * 채널 테스트 발송 결과 DTO.
 *
 * <p>{@code POST /api/v1/channels/settings/{channel}/test} 응답 형태. SMTP 테스트({@code
 * SettingsController#testSmtpSettings})와 동일한 {@code success/message} 구조를 사용하여 프론트엔드 토스트 처리 로직을
 * 통일한다.
 *
 * @param success 발송 성공 여부 — true면 채널 정상, false면 message에 사유
 * @param message 사용자에게 보일 결과 메시지 (성공 시 안내, 실패 시 사유)
 */
public record ChannelTestResult(boolean success, String message) {}

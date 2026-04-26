package com.smartfirehub.notification;

/** Channel 별 외부 인증 방식. requiresBinding 여부와 refresh 책임을 결정. */
public enum AuthStrategy {
  NONE, // CHAT (binding 불필요)
  EMAIL_ADDRESS, // EMAIL (display_address만 사용)
  OAUTH, // KAKAO (사용자 OAuth refresh)
  BOT_TOKEN // SLACK (워크스페이스 봇 토큰 + 사용자 매핑)
}

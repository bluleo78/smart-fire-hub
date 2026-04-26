package com.smartfirehub.notification;

import com.smartfirehub.notification.repository.UserChannelBinding;

/** binding이 필요한 채널은 추가로 토큰 갱신 책임을 가진다. */
public interface BoundChannel extends Channel {
  /** 토큰 만료 직전이면 refresh, 실패 시 binding.status=TOKEN_EXPIRED. */
  RefreshResult refreshIfNeeded(UserChannelBinding binding);

  sealed interface RefreshResult {
    record Refreshed(String newAccessToken, String newRefreshToken, java.time.Instant expiresAt)
        implements RefreshResult {}

    record StillValid() implements RefreshResult {}

    record Failed(String reason) implements RefreshResult {}
  }
}

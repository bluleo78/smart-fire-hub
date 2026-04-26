package com.smartfirehub.notification;

/** 영구 실패 분류. 후속 처리(사용자/발송자 통보 여부) 결정. */
public enum PermanentFailureReason {
  BINDING_REQUIRED,
  TOKEN_EXPIRED,
  RATE_LIMIT_EXHAUSTED,
  RECIPIENT_INVALID,
  UNRECOVERABLE
}

package com.smartfirehub.notification.channels;

/** firehub-channel /send 호출 실패 시 발생하는 예외. */
public class ChannelHttpException extends RuntimeException {
    private final int statusCode;

    public ChannelHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() { return statusCode; }

    /** 인증 오류 여부 — OutboxWorker가 PermanentFailure로 처리한다. */
    public boolean isAuthError() { return statusCode == 401; }
}

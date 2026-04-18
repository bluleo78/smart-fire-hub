package com.smartfirehub.notification;

/** 알림 채널 종류 식별. 새 채널 추가 시 여기에 enum 값 추가 + Channel 구현체 1개. */
public enum ChannelType {
    CHAT,    // 웹 인박스 (안전망, opt-out 불가)
    EMAIL,
    KAKAO,
    SLACK
}

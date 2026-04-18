package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;

/** 사용자 채널별 opt-out 조회. 구현체는 Task 5에서 jOOQ로 작성. */
public interface UserChannelPreferenceRepository {
    /** 기본값 true (테이블에 row 없으면 enabled=true로 간주). */
    boolean isEnabled(long userId, ChannelType channelType);
}

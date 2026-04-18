package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;

/** 사용자 채널별 opt-out 조회·변경. */
public interface UserChannelPreferenceRepository {
    /** 기본값 true (테이블에 row 없으면 enabled=true로 간주). */
    boolean isEnabled(long userId, ChannelType channelType);

    /**
     * 채널 알림 수신 여부를 upsert.
     *
     * <p>CHAT 채널은 DB CHECK 제약으로 disable 불가이므로 호출 전에 검증할 것.
     *
     * @param userId 대상 사용자 ID
     * @param channelType 변경할 채널
     * @param enabled 활성화 여부
     */
    void setEnabled(long userId, ChannelType channelType, boolean enabled);
}

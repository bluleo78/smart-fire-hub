package com.smartfirehub.notification;

import java.util.Set;

/** 단일 수신자의 발송 요청 (사용자 또는 외부 주소 단위). */
public record Recipient(
        Long userId,                       // null = 외부 주소 직접 발송
        String externalAddressIfAny,
        Set<ChannelType> requestedChannels
) {}

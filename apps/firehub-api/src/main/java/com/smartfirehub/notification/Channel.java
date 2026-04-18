package com.smartfirehub.notification;

/** 모든 발송 채널의 공통 SPI. 구현체는 Spring Bean으로 등록. */
public interface Channel {
    ChannelType type();
    AuthStrategy authStrategy();
    DeliveryResult deliver(DeliveryContext ctx);

    /** 사용자별 binding 필요 여부. authStrategy로 자동 판정. */
    default boolean requiresBinding() {
        return authStrategy() == AuthStrategy.OAUTH || authStrategy() == AuthStrategy.BOT_TOKEN;
    }
}

package com.smartfirehub.notification.service;

import com.smartfirehub.notification.ChannelType;
import java.util.List;
import java.util.Map;

/** RoutingResolver 결과: 실제 enqueue할 채널 목록 + skip 사유. */
public record ResolvedRouting(
    List<ChannelType> resolvedChannels,
    Map<ChannelType, String> skippedReasons, // 채널 → OPTED_OUT|BINDING_MISSING
    boolean forcedChatFallback // resolved가 비어 CHAT 강제됐는지
    ) {}

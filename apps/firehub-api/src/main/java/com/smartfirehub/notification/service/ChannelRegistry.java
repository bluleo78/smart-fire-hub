package com.smartfirehub.notification.service;

import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 모든 Channel 구현체 등록. authStrategy 조회 + 발송 시 lookup. */
@Component
public class ChannelRegistry {

  private final Map<ChannelType, Channel> channels = new EnumMap<>(ChannelType.class);

  public ChannelRegistry(List<Channel> all) {
    for (Channel c : all) {
      channels.put(c.type(), c);
    }
  }

  public Channel get(ChannelType type) {
    Channel c = channels.get(type);
    if (c == null) throw new IllegalStateException("No channel registered: " + type);
    return c;
  }

  public AuthStrategy authStrategyOf(ChannelType type) {
    return get(type).authStrategy();
  }
}

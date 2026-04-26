package com.smartfirehub.notification.service;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.notification.repository.UserChannelPreferenceRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 라우팅 매트릭스 (Spec 6장): - opt-out 채널 skip - binding 필요한데 없는 채널 skip - 모두 skip되면 CHAT 강제 (안전망) - CHAT은
 * DB CHECK로 항상 enabled
 *
 * <p>authStrategy는 {@link ChannelType} enum 자체에서 읽어 Channel Bean 유무와 무관하게 판단.
 */
@Component
public class RoutingResolver {

  private final UserChannelPreferenceRepository preferenceRepo;
  private final UserChannelBindingRepository bindingRepo;

  public RoutingResolver(
      UserChannelPreferenceRepository preferenceRepo, UserChannelBindingRepository bindingRepo) {
    this.preferenceRepo = preferenceRepo;
    this.bindingRepo = bindingRepo;
  }

  public ResolvedRouting resolve(Recipient recipient) {
    List<ChannelType> resolved = new ArrayList<>();
    Map<ChannelType, String> skipped = new EnumMap<>(ChannelType.class);

    for (ChannelType ch : recipient.requestedChannels()) {
      // CHAT은 opt-out 불가 (DB CHECK로 보장되지만 방어적으로도 건너뛰지 않음)
      if (ch != ChannelType.CHAT && !preferenceRepo.isEnabled(recipient.userId(), ch)) {
        skipped.put(ch, "OPTED_OUT");
        continue;
      }
      if (ch.requiresBinding() && bindingRepo.findActive(recipient.userId(), ch).isEmpty()) {
        skipped.put(ch, "BINDING_MISSING");
        continue;
      }
      resolved.add(ch);
    }

    boolean forced = false;
    if (resolved.isEmpty()) {
      resolved.add(ChannelType.CHAT);
      forced = true;
    }
    return new ResolvedRouting(resolved, skipped, forced);
  }
}

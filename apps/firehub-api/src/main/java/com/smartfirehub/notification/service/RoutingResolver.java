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

  /**
   * <b>테넌트 컨텍스트가 반드시 있어야 한다(P2-f).</b> 아래 {@code preferenceRepo.isEnabled} 는
   * 행이 없으면 기본 {@code true}, {@code bindingRepo.findActive} 는 0행이므로, 컨텍스트 없이 돌면
   * 정책(V107) 이후 <b>모든 채널이 {@code BINDING_MISSING} 으로 스킵되고 CHAT 폴백만 남는</b> 조용한
   * 열화가 된다(에러도 로그도 없다).
   *
   * <p>확인 결과 이 경로는 배선이 불필요하다: 유일한 호출자 {@code NotificationDispatcher.enqueue}
   * 의 유일한 호출자는 {@code ProactiveJobAsyncRunner}({@code @Async("pipelineExecutor")}) 이고, 그
   * 실행기는 {@code AsyncConfig} 에서 {@code TenantContextTaskDecorator} 를 달아 제출 스레드의
   * 컨텍스트를 승계한다. 제출 스레드는 HTTP(JWT) 또는 P2-e 가 배선한 스케줄러라 둘 다 컨텍스트를
   * 갖는다. <b>컨텍스트 없는 새 호출자를 붙이면 이 계약이 깨진다.</b>
   */
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

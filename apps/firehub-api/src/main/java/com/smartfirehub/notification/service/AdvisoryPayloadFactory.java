package com.smartfirehub.notification.service;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.Payload;
import java.util.List;
import java.util.Map;

/**
 * CHAT 강제 폴백 시 사용자에게 보일 안내 메시지 생성. 스펙 6장: 외부 채널이 모두 OPTED_OUT 또는 BINDING_MISSING일 때 CHAT 행과 함께
 * enqueue.
 */
class AdvisoryPayloadFactory {

  /** 비공개 생성자 — 유틸리티 클래스. */
  private AdvisoryPayloadFactory() {}

  static Payload build(Map<ChannelType, String> skippedReasons) {
    StringBuilder summary = new StringBuilder("외부 채널 발송이 불가능해 웹 인박스에만 표시됩니다.");
    if (!skippedReasons.isEmpty()) {
      summary.append(" 원인: ").append(skippedReasons);
    }
    return new Payload(
        Payload.PayloadType.STANDARD,
        "🔔 알림 채널 안내",
        summary.toString(),
        List.of(new Payload.Section("연동/설정 변경", "채널 연동 또는 수신 설정을 변경하려면 설정 페이지에서 진행하세요.")),
        List.of(new Payload.Link("설정 페이지", "/settings/channels")),
        List.of(),
        Map.of(),
        Map.of());
  }
}

package com.smartfirehub.proactive.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Proactive Job config JSONB 파싱 유틸리티.
 *
 * <p>구 형식: { "channels": ["CHAT", "EMAIL"], "targets": "ALL" } 신 형식: { "channels": [{ "type":
 * "CHAT", "recipientUserIds": [1, 2] }, { "type": "EMAIL", "recipientEmails": ["a@b.com"] }] }
 *
 * <p>두 형식 모두 하위 호환하여 파싱한다.
 */
public class ProactiveConfigParser {

  // RFC 5322 간략 검증 패턴
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  /** 채널별 수신자 설정 레코드. */
  public record ChannelConfig(
      String type, List<Long> recipientUserIds, List<String> recipientEmails, boolean attachPdf) {}

  /**
   * config JSONB에서 채널 설정 목록 파싱.
   *
   * @param config job.config() 맵
   * @return 채널 설정 목록. config가 null이거나 channels가 없으면 빈 리스트.
   */
  @SuppressWarnings("unchecked")
  public static List<ChannelConfig> parseChannels(Map<String, Object> config) {
    if (config == null) return List.of();
    Object channelsObj = config.get("channels");
    if (!(channelsObj instanceof List<?> list) || list.isEmpty()) return List.of();

    List<ChannelConfig> result = new ArrayList<>();
    Object first = list.get(0);

    if (first instanceof String) {
      // 구 형식: ["CHAT", "EMAIL"]
      for (Object element : list) {
        if (element instanceof String type) {
          result.add(new ChannelConfig(type, List.of(), List.of(), false));
        }
      }
    } else if (first instanceof Map) {
      // 신 형식: [{ type: "CHAT", recipientUserIds: [...], recipientEmails: [...], attachPdf: true }]
      for (Object element : list) {
        if (element instanceof Map<?, ?> map) {
          String type = map.get("type") != null ? map.get("type").toString() : null;
          if (type == null) continue;

          List<Long> userIds = parseUserIds((List<?>) map.get("recipientUserIds"));
          List<String> emails = parseEmails((List<?>) map.get("recipientEmails"));
          boolean attachPdf = Boolean.TRUE.equals(map.get("attachPdf"));
          result.add(new ChannelConfig(type, userIds, emails, attachPdf));
        }
      }
    }

    return Collections.unmodifiableList(result);
  }

  /** 특정 채널 타입의 설정을 반환. 없으면 Optional.empty(). */
  public static Optional<ChannelConfig> getChannelConfig(
      Map<String, Object> config, String channelType) {
    return parseChannels(config).stream().filter(ch -> ch.type().equals(channelType)).findFirst();
  }

  /** 채널 타입 문자열 목록 반환. 기존 getConfigChannels() 대체. 구/신 형식 모두 처리. */
  public static List<String> getChannelTypes(Map<String, Object> config) {
    return parseChannels(config).stream().map(ChannelConfig::type).toList();
  }

  /** 이메일 형식 검증. 잘못된 형식이 있으면 IllegalArgumentException 발생. */
  public static void validateEmails(List<String> emails) {
    if (emails == null) return;
    for (String email : emails) {
      validateEmail(email);
    }
  }

  /** 단일 이메일 형식 검증. 잘못된 형식이면 IllegalArgumentException 발생. */
  public static void validateEmail(String email) {
    if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
      throw new IllegalArgumentException("잘못된 이메일 형식입니다: " + email);
    }
  }

  private static List<Long> parseUserIds(List<?> raw) {
    if (raw == null) return List.of();
    List<Long> result = new ArrayList<>();
    for (Object item : raw) {
      if (item instanceof Number n) {
        result.add(n.longValue());
      }
    }
    return Collections.unmodifiableList(result);
  }

  private static List<String> parseEmails(List<?> raw) {
    if (raw == null) return List.of();
    List<String> result = new ArrayList<>();
    for (Object item : raw) {
      if (item instanceof String s) {
        result.add(s);
      }
    }
    return Collections.unmodifiableList(result);
  }
}

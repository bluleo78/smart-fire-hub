package com.smartfirehub.notification;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 채널 발송용 페이로드. Standard 필드 + 선택적 channel-raw override. */
public record Payload(
        PayloadType type,
        String title,
        String summary,
        List<Section> sections,
        List<Link> links,
        List<Media> media,
        Map<String, Object> metadata,
        Map<ChannelType, JsonNode> rawOverrideByChannel
) {
    public enum PayloadType { STANDARD, OVERRIDE }
    public record Section(String heading, String bodyMd) {}
    public record Link(String label, String url) {}
    public record Media(String type, String url, String alt) {}
}

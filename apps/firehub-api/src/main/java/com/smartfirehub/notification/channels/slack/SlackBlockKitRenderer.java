package com.smartfirehub.notification.channels.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.notification.Payload;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Slack Block Kit 렌더러.
 *
 * <p>StandardPayload를 Slack Block Kit JSON 배열 문자열로 변환한다. 구성 순서: header(title) → section(summary) →
 * sections 항목 → actions(links).
 *
 * <p>길이 제한 방어: summary 2000자, sections 20개, links 5개. Slack blocks 배열은 최대 50 요소, 각 텍스트 3000자 제한이
 * 있으나 위 제한으로 충분히 커버된다.
 */
@Component
public class SlackBlockKitRenderer {

  /** summary mrkdwn 최대 길이. Slack section text 3000자 제한보다 여유를 두고 설정. */
  private static final int MAX_SUMMARY_LEN = 2000;

  /** sections 최대 개수. 50개 blocks 제한 안에 여유 있게 설정. */
  private static final int MAX_SECTIONS = 20;

  /** actions block 버튼 최대 개수. Slack actions element 최대 25개이나 UX 고려 5개로 제한. */
  private static final int MAX_LINKS = 5;

  /** Slack header block type text 최대 길이. */
  private static final int MAX_HEADER_LEN = 150;

  private final ObjectMapper objectMapper;

  public SlackBlockKitRenderer() {
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Payload를 Block Kit JSON 배열 문자열로 변환.
   *
   * <p>반환 형식: {@code [{"type":"header",...}, {"type":"section",...}, ...]}
   *
   * @param payload 발송 페이로드
   * @return Block Kit blocks JSON 배열 문자열
   */
  public String renderBlocksJson(Payload payload) {
    ArrayNode blocks = objectMapper.createArrayNode();

    // header block: title (plain_text, 150자 제한)
    if (payload.title() != null && !payload.title().isBlank()) {
      String title = truncate(payload.title(), MAX_HEADER_LEN);
      ObjectNode headerBlock = objectMapper.createObjectNode();
      headerBlock.put("type", "header");
      ObjectNode textNode = objectMapper.createObjectNode();
      textNode.put("type", "plain_text");
      textNode.put("text", title);
      textNode.put("emoji", true);
      headerBlock.set("text", textNode);
      blocks.add(headerBlock);
    }

    // section block: summary (mrkdwn, 2000자 제한)
    if (payload.summary() != null && !payload.summary().isBlank()) {
      String summary = truncate(payload.summary(), MAX_SUMMARY_LEN);
      blocks.add(buildSectionBlock(summary));
    }

    // sections 항목별 section block: *heading*\nbodyMd 형식의 mrkdwn
    List<Payload.Section> sections = payload.sections();
    if (sections != null && !sections.isEmpty()) {
      int count = Math.min(sections.size(), MAX_SECTIONS);
      for (int i = 0; i < count; i++) {
        Payload.Section section = sections.get(i);
        String text = buildSectionText(section);
        if (!text.isBlank()) {
          blocks.add(buildSectionBlock(truncate(text, 2000)));
        }
      }
    }

    // actions block: links → button elements (url 링크)
    List<Payload.Link> links = payload.links();
    if (links != null && !links.isEmpty()) {
      int count = Math.min(links.size(), MAX_LINKS);
      ArrayNode elements = objectMapper.createArrayNode();
      for (int i = 0; i < count; i++) {
        Payload.Link link = links.get(i);
        if (link.label() != null && link.url() != null) {
          elements.add(buildButtonElement(link.label(), link.url()));
        }
      }
      if (elements.size() > 0) {
        ObjectNode actionsBlock = objectMapper.createObjectNode();
        actionsBlock.put("type", "actions");
        actionsBlock.set("elements", elements);
        blocks.add(actionsBlock);
      }
    }

    try {
      return objectMapper.writeValueAsString(blocks);
    } catch (Exception e) {
      // JSON 직렬화 실패는 실질적으로 발생하지 않으나 fallback으로 빈 배열 반환
      return "[]";
    }
  }

  /**
   * 푸시 알림용 fallback 텍스트 반환.
   *
   * <p>Slack push notification과 접근성 도구에서 표시되는 텍스트. title + "\n" + summary, 500자 트렁케이트.
   *
   * @param payload 발송 페이로드
   * @return fallback 텍스트 문자열
   */
  public String renderFallbackText(Payload payload) {
    StringBuilder sb = new StringBuilder();
    if (payload.title() != null && !payload.title().isBlank()) {
      sb.append(payload.title());
    }
    if (payload.summary() != null && !payload.summary().isBlank()) {
      if (sb.length() > 0) {
        sb.append("\n");
      }
      sb.append(payload.summary());
    }
    return truncate(sb.toString(), 500);
  }

  /**
   * mrkdwn section block 생성 헬퍼.
   *
   * @param mrkdwn 본문 mrkdwn 텍스트
   * @return section block ObjectNode
   */
  private ObjectNode buildSectionBlock(String mrkdwn) {
    ObjectNode block = objectMapper.createObjectNode();
    block.put("type", "section");
    ObjectNode textNode = objectMapper.createObjectNode();
    textNode.put("type", "mrkdwn");
    textNode.put("text", mrkdwn);
    block.set("text", textNode);
    return block;
  }

  /**
   * Section(heading, bodyMd)을 mrkdwn 텍스트로 변환.
   *
   * <p>형식: {@code *heading*\nbodyMd}. heading 또는 bodyMd가 없으면 해당 부분 생략.
   *
   * @param section Payload.Section 항목
   * @return mrkdwn 텍스트
   */
  private String buildSectionText(Payload.Section section) {
    StringBuilder sb = new StringBuilder();
    if (section.heading() != null && !section.heading().isBlank()) {
      sb.append("*").append(section.heading()).append("*");
    }
    if (section.bodyMd() != null && !section.bodyMd().isBlank()) {
      if (sb.length() > 0) {
        sb.append("\n");
      }
      sb.append(section.bodyMd());
    }
    return sb.toString();
  }

  /**
   * Link를 Slack button element로 변환.
   *
   * @param label 버튼 레이블
   * @param url 클릭 시 이동할 URL
   * @return button element ObjectNode
   */
  private ObjectNode buildButtonElement(String label, String url) {
    ObjectNode button = objectMapper.createObjectNode();
    button.put("type", "button");
    ObjectNode textNode = objectMapper.createObjectNode();
    textNode.put("type", "plain_text");
    textNode.put("text", truncate(label, 75));
    textNode.put("emoji", true);
    button.set("text", textNode);
    button.put("url", url);
    return button;
  }

  /**
   * 문자열을 maxLen 이하로 트렁케이트. 초과 시 끝에 "…" 추가.
   *
   * @param s 원본 문자열
   * @param maxLen 최대 길이 (트렁케이트 기준)
   * @return 트렁케이트된 문자열
   */
  private static String truncate(String s, int maxLen) {
    if (s == null) {
      return "";
    }
    if (s.length() <= maxLen) {
      return s;
    }
    return s.substring(0, maxLen) + "…";
  }
}

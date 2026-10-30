package com.smartfirehub.proactive.util;

import com.smartfirehub.proactive.exception.ProactiveJobException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 리포트 양식 섹션 서버 검증(WD-298).
 *
 * <p>화면 JSON 편집기만 key 를 검사하고 서버는 그대로 저장해, API·AI 도구로 만든 깨진 양식(중복 key·key 없음·알 수 없는 type·빈 이름)이
 * 목록·상세에 그대로 나왔다. 저장 전에 같은 규칙으로 검사해 400(ProactiveJobException)으로 거절한다.
 *
 * <ul>
 *   <li>key — 필수, 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만(웹 SECTION_KEY_PATTERN 과 같다), 하위(children)까지 트리 전체에서 중복
 *       불가
 *   <li>type — 생략 가능(AI 도구가 생략해 보낸다), 적으면 정해진 타입만
 *   <li>label — 필수, 공백만은 안 된다
 * </ul>
 */
public final class ReportTemplateSectionValidator {

  /** 웹 template-section-types.ts 의 SECTION_KEY_PATTERN 과 같은 규칙. */
  private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

  /** 웹 SectionType 과 같은 집합 — 화면이 그릴 수 있는 타입만 받는다. */
  public static final Set<String> SECTION_TYPES =
      Set.of(
          "text",
          "cards",
          "list",
          "table",
          "comparison",
          "alert",
          "timeline",
          "chart",
          "recommendation",
          "group",
          "divider");

  private ReportTemplateSectionValidator() {}

  /** 섹션 트리 전체를 검사한다. 첫 위반에서 한국어 사유로 예외를 던진다. null 이면(수정 요청에서 섹션을 바꾸지 않음) 검사하지 않는다. */
  public static void validate(List<Map<String, Object>> sections) {
    if (sections == null) {
      return;
    }
    validateList(sections, null, new HashSet<>());
  }

  /**
   * 한 단계 섹션 목록을 검사하고 group 의 children 으로 내려간다 — seen 은 트리 전체에서 공유해 단계가 달라도 중복을 잡는다. trail 은 상위 key
   * 경로(최상위는 null).
   */
  private static void validateList(List<?> sections, String trail, Set<String> seen) {
    for (int i = 0; i < sections.size(); i++) {
      // 위치 안내는 웹 문구(「(a > b 하위)」)와 맞추고, 최상위는 몇 번째 섹션인지 알려 준다.
      String where = trail != null ? " (" + trail + " 하위)" : " (" + (i + 1) + "번째 섹션)";
      if (!(sections.get(i) instanceof Map<?, ?> section)) {
        throw new ProactiveJobException("섹션 형식이 올바르지 않습니다" + where + ".");
      }
      Object keyValue = section.get("key");
      String key = keyValue instanceof String s ? s : null;
      if (key == null || key.isBlank()) {
        throw new ProactiveJobException("섹션 Key가 없습니다" + where + ". 영문 소문자, 숫자, 밑줄로 Key를 정하세요.");
      }
      if (!KEY_PATTERN.matcher(key).matches()) {
        throw new ProactiveJobException(
            "섹션 Key \"" + key + "\"가 올바르지 않습니다" + where + ". 영문 소문자, 숫자, 밑줄만 사용하세요.");
      }
      if (!seen.add(key)) {
        throw new ProactiveJobException("섹션 Key가 중복되었습니다: \"" + key + "\"" + where);
      }
      Object type = section.get("type");
      if (type != null && !(type instanceof String t && SECTION_TYPES.contains(t))) {
        throw new ProactiveJobException(
            "섹션 \""
                + key
                + "\"의 타입 \""
                + type
                + "\"은(는) 지원하지 않습니다. 사용할 수 있는 타입: "
                + String.join(", ", SECTION_TYPES.stream().sorted().toList()));
      }
      Object label = section.get("label");
      if (!(label instanceof String l) || l.isBlank()) {
        throw new ProactiveJobException("섹션 \"" + key + "\"의 이름(label)이 비어 있습니다.");
      }
      Object children = section.get("children");
      if (children instanceof List<?> childList) {
        validateList(childList, trail != null ? trail + " > " + key : key, seen);
      } else if (children != null) {
        throw new ProactiveJobException("섹션 \"" + key + "\"의 하위 섹션(children)은 목록이어야 합니다.");
      }
    }
  }
}

package com.smartfirehub.proactive.service;

import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.proactive.dto.GenerateReportTemplateResponse;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.util.ReportTemplateSectionValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 목표 기반 리포트 양식 섹션 구성 생성(WD-455).
 *
 * <p>ai-agent 가 만든 섹션을 저장 때와 같은 검사기({@link ReportTemplateSectionValidator}, WD-298)로 검사해, 통과한 구성만
 * 화면에 돌려준다. 저장은 하지 않는다 — 화면이 미리보기로 보여 주고, 작업 생성 때 기존 흐름(WD-297)이 「&lt;작업 이름&gt; 양식」으로 저장·연결한다. 그래서
 * 여기서 통과한 구성은 그 저장 단계에서 다시 거절되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportTemplateGenerationService {

  /** 섹션 수 상한 — 프롬프트는 3~8개를 요구한다. 모델이 지나치게 많이 만들면 쓸 수 없는 결과로 본다. */
  static final int MAX_SECTIONS = 20;

  /** AI 결과가 섹션 규칙을 어겼을 때 사용자 문구. 사용자의 잘못이 아니므로 400 이 아니라 502 로 알린다. */
  static final String INVALID_OUTPUT_MESSAGE = "AI가 만든 섹션 구성이 양식 규칙에 맞지 않습니다. 다시 시도해 주세요.";

  private final ReportTemplateAiClient aiClient;

  /**
   * 질문으로 섹션 구성을 생성하고 검사한다.
   *
   * @throws ExternalServiceException ai-agent 실패이거나 결과가 비었거나 섹션 규칙(WD-298)을 어긴 경우(502)
   */
  public GenerateReportTemplateResponse generate(String question, Long userId) {
    List<Map<String, Object>> raw = aiClient.generateSections(userId, question.trim());
    // validate() 는 null 을 "섹션을 바꾸지 않음"으로 보고 통과시키므로, 비었으면 여기서 먼저 막는다.
    if (raw == null || raw.isEmpty() || raw.size() > MAX_SECTIONS) {
      log.warn("AI template generation returned {} sections", raw == null ? null : raw.size());
      throw new ExternalServiceException(INVALID_OUTPUT_MESSAGE);
    }
    List<Map<String, Object>> sections = new ArrayList<>();
    for (Object item : raw) {
      if (!(item instanceof Map<?, ?> section)) {
        throw new ExternalServiceException(INVALID_OUTPUT_MESSAGE);
      }
      sections.add(sanitize(section));
    }
    try {
      ReportTemplateSectionValidator.validate(sections);
    } catch (ProactiveJobException e) {
      // 검사기 예외는 400(사용자 입력 오류)으로 매핑되므로, AI 결과의 문제임을 드러내도록 502 로 바꾼다. 사유는 로그에 남긴다.
      log.warn("AI template generation output rejected: {}", e.getMessage());
      throw new ExternalServiceException(INVALID_OUTPUT_MESSAGE, e);
    }
    return new GenerateReportTemplateResponse(sections);
  }

  /**
   * 웹 TemplateSection 이 쓰는 필드(key·type·label·instruction·required)만 남긴다 — 모델이 끼워 넣은 다른
   * 필드(static·content· children 등)가 양식으로 저장되지 않게 한다. type 이 없으면 text 로 둔다(검사기는 생략을 허용하지만 화면 미리보기는
   * 타입이 필요하다).
   */
  private static Map<String, Object> sanitize(Map<?, ?> section) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("key", section.get("key"));
    Object type = section.get("type");
    out.put("type", type != null ? type : "text");
    out.put("label", section.get("label"));
    if (section.get("instruction") instanceof String instruction && !instruction.isBlank()) {
      out.put("instruction", instruction);
    }
    if (Boolean.TRUE.equals(section.get("required"))) {
      out.put("required", true);
    }
    return out;
  }
}

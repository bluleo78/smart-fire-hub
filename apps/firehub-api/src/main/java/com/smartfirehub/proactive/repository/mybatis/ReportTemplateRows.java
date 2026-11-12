package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.LocalDateTime;

/** ReportTemplateMapper 의 평평한 행·INSERT 홀더 모음(WD-709). sections 는 JSON 문자열 그대로(구현이 목록으로 푼다). */
public final class ReportTemplateRows {

  private ReportTemplateRows() {}

  /** 양식 한 줄(상세) — sections·style 포함. */
  public record TemplateRow(
      Long id,
      String name,
      String description,
      String sectionsJson,
      String style,
      Long userId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /**
   * 양식 목록 한 줄 — sections·style 본문은 읽지 않고 섹션 개수만 DB 에서 센다(PlatformSql.jsonArrayLength, jOOQ 의
   * jsonb_array_length 와 같은 값).
   */
  public record SummaryRow(
      Long id,
      String name,
      String description,
      Integer sectionCount,
      Long userId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** 잠근 행의 수정 시각 — id 를 함께 읽어 「행 없음」과 「수정 시각이 빈 행」을 가른다. */
  public record VersionRow(Long id, LocalDateTime updatedAt) {}

  /** 양식 INSERT 파라미터 겸 생성 키 홀더(Tibero 에는 RETURNING 이 없어 JDBC 생성 키가 id 에 써 넣는다). */
  public static class TemplateInsert extends GeneratedId {
    private final String name;
    private final String description;
    private final String sectionsJson;
    private final String style;
    private final Long userId;

    public TemplateInsert(
        String name, String description, String sectionsJson, String style, Long userId) {
      this.name = name;
      this.description = description;
      this.sectionsJson = sectionsJson;
      this.style = style;
      this.userId = userId;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public String getSectionsJson() {
      return sectionsJson;
    }

    public String getStyle() {
      return style;
    }

    public Long getUserId() {
      return userId;
    }
  }
}

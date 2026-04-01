package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReportTemplateRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  private static final Table<?> REPORT_TEMPLATE = table(name("report_template"));
  private static final Field<Long> RT_ID = field(name("report_template", "id"), Long.class);
  private static final Field<String> RT_NAME = field(name("report_template", "name"), String.class);
  private static final Field<String> RT_DESCRIPTION =
      field(name("report_template", "description"), String.class);
  private static final Field<JSONB> RT_SECTIONS =
      field(name("report_template", "sections"), JSONB.class);
  private static final Field<Long> RT_USER_ID =
      field(name("report_template", "user_id"), Long.class);
  private static final Field<LocalDateTime> RT_CREATED_AT =
      field(name("report_template", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> RT_UPDATED_AT =
      field(name("report_template", "updated_at"), LocalDateTime.class);

  public List<ReportTemplateResponse> findAllForUser(Long userId) {
    return dsl.select(
            RT_ID, RT_NAME, RT_DESCRIPTION, RT_SECTIONS, RT_USER_ID, RT_CREATED_AT, RT_UPDATED_AT)
        .from(REPORT_TEMPLATE)
        .where(RT_USER_ID.isNull().or(RT_USER_ID.eq(userId)))
        .orderBy(RT_ID.asc())
        .fetch(
            r ->
                toResponse(
                    r.get(RT_ID),
                    r.get(RT_NAME),
                    r.get(RT_DESCRIPTION),
                    r.get(RT_SECTIONS),
                    r.get(RT_USER_ID),
                    r.get(RT_CREATED_AT),
                    r.get(RT_UPDATED_AT)));
  }

  public Optional<ReportTemplateResponse> findById(Long id) {
    return dsl.select(
            RT_ID, RT_NAME, RT_DESCRIPTION, RT_SECTIONS, RT_USER_ID, RT_CREATED_AT, RT_UPDATED_AT)
        .from(REPORT_TEMPLATE)
        .where(RT_ID.eq(id))
        .fetchOptional(
            r ->
                toResponse(
                    r.get(RT_ID),
                    r.get(RT_NAME),
                    r.get(RT_DESCRIPTION),
                    r.get(RT_SECTIONS),
                    r.get(RT_USER_ID),
                    r.get(RT_CREATED_AT),
                    r.get(RT_UPDATED_AT)));
  }

  public Long create(
      String name, String description, List<Map<String, Object>> sections, Long userId) {
    try {
      String sectionsJson = objectMapper.writeValueAsString(sections);
      return dsl.insertInto(REPORT_TEMPLATE)
          .set(RT_NAME, name)
          .set(RT_DESCRIPTION, description)
          .set(RT_SECTIONS, JSONB.valueOf(sectionsJson))
          .set(RT_USER_ID, userId)
          .returning(RT_ID)
          .fetchOne(r -> r.get(RT_ID));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize sections", e);
    }
  }

  public void update(
      Long id, Long userId, String name, String description, List<Map<String, Object>> sections) {
    // 빌트인 템플릿(user_id IS NULL)은 수정 불가
    try {
      var query = dsl.update(REPORT_TEMPLATE);
      var step = query.set(RT_UPDATED_AT, LocalDateTime.now());
      if (name != null) step = step.set(RT_NAME, name);
      if (description != null) step = step.set(RT_DESCRIPTION, description);
      if (sections != null) {
        String sectionsJson = objectMapper.writeValueAsString(sections);
        step = step.set(RT_SECTIONS, JSONB.valueOf(sectionsJson));
      }
      step.where(RT_ID.eq(id).and(RT_USER_ID.eq(userId))).execute();
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize sections", e);
    }
  }

  public void delete(Long id, Long userId) {
    // 빌트인 템플릿(user_id IS NULL)은 삭제 불가
    dsl.deleteFrom(REPORT_TEMPLATE).where(RT_ID.eq(id).and(RT_USER_ID.eq(userId))).execute();
  }

  private ReportTemplateResponse toResponse(
      Long id,
      String name,
      String description,
      JSONB sections,
      Long userId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    try {
      List<Map<String, Object>> sectionList =
          sections != null
              ? objectMapper.readValue(sections.data(), new TypeReference<>() {})
              : List.of();
      return new ReportTemplateResponse(
          id, name, description, sectionList, userId, userId == null, createdAt, updatedAt);
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize sections", e);
    }
  }
}

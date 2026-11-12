package com.smartfirehub.proactive.repository.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.mybatis.PlatformJson;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateRows.SummaryRow;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateRows.TemplateInsert;
import com.smartfirehub.proactive.repository.mybatis.ReportTemplateRows.TemplateRow;
import com.smartfirehub.proactive.util.ProactiveTime;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReportTemplateRepository 의 MyBatis 구현(WD-709) — {@code JooqReportTemplateRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 배경 호출자({@code
 * ProactiveJobAsyncRunner.executeJob} 의 {@code @Async})에 트랜잭션이 없어, 없으면 RLS 컨텍스트가 심기지 않아 0행이 된다(전파
 * REQUIRED 라 컨트롤러 경로는 불변).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisReportTemplateRepository implements ReportTemplateRepository {

  private final ReportTemplateMapper mapper;
  private final ObjectMapper objectMapper;

  public MyBatisReportTemplateRepository(ReportTemplateMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  /**
   * 섹션 개수는 sections 본문을 읽지 않고 DB 에서 센다(PlatformSql.jsonArrayLength) — jOOQ 의 jsonb_array_length 와
   * 같이 원소 종류와 무관하게 센 값이다.
   */
  @Override
  public List<ReportTemplateSummaryResponse> findAllForUser(Long userId, int page, int size) {
    return mapper.findAllForUser(userId, size, page * size).stream()
        .map(MyBatisReportTemplateRepository::toSummary)
        .toList();
  }

  @Override
  public Optional<ReportTemplateResponse> findById(Long id) {
    return Optional.ofNullable(mapper.findById(id)).map(this::toResponse);
  }

  @Override
  public Optional<ReportTemplateResponse> findVisibleTo(Long id, Long userId) {
    return Optional.ofNullable(mapper.findVisibleTo(id, userId)).map(this::toResponse);
  }

  @Override
  public Long create(
      String name,
      String description,
      List<Map<String, Object>> sections,
      String style,
      Long userId) {
    TemplateInsert insert =
        new TemplateInsert(
            name,
            blankToNull(description),
            PlatformJson.write(objectMapper, sections, "sections"),
            blankToNull(style),
            userId);
    mapper.insert(insert);
    return insert.getId();
  }

  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.lockVersionById(id))
        .map(row -> new LockedRowVersion(row.updatedAt()));
  }

  /**
   * 넘긴 필드만 고친다 — null 은 「바꾸지 않음」, 빈 문자열·공백은 「비움」(NULL, WD-135). 수정 시각은 자바 UTC 값을 마이크로초로 잘라 바인딩하고,
   * 고친 행이 있을 때 그 값을 돌려준다(저장 정밀도와 같아 다음 저장의 기준 버전과 어긋나지 않는다).
   */
  @Override
  public LocalDateTime update(
      Long id,
      Long userId,
      String name,
      String description,
      List<Map<String, Object>> sections,
      String style) {
    LocalDateTime updatedAt = ProactiveTime.nowUtc().truncatedTo(ChronoUnit.MICROS);
    int updated =
        mapper.update(
            id,
            userId,
            updatedAt,
            name,
            description != null,
            blankToNull(description),
            sections == null ? null : PlatformJson.write(objectMapper, sections, "sections"),
            style != null,
            blankToNull(style));
    return updated == 1 ? updatedAt : null;
  }

  @Override
  public int delete(Long id, Long userId) {
    return mapper.delete(id, userId);
  }

  /** 공백뿐인 설명·작성 스타일은 NULL 로 저장한다(WD-135) — jOOQ 구현과 같다. */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private ReportTemplateResponse toResponse(TemplateRow r) {
    List<Map<String, Object>> sections =
        PlatformJson.toMapList(objectMapper, r.sectionsJson(), "sections");
    return new ReportTemplateResponse(
        r.id(),
        r.name(),
        r.description(),
        sections == null ? List.of() : sections,
        r.style(),
        r.userId(),
        r.userId() == null,
        r.createdAt(),
        r.updatedAt());
  }

  /** 목록 행 → 응답. 섹션 개수는 SQL 이 센 값(sections 열은 NOT NULL 이라 null 이 오지 않는다). */
  private static ReportTemplateSummaryResponse toSummary(SummaryRow r) {
    return new ReportTemplateSummaryResponse(
        r.id(),
        r.name(),
        r.description(),
        r.sectionCount(),
        r.userId(),
        r.userId() == null,
        r.createdAt(),
        r.updatedAt());
  }
}

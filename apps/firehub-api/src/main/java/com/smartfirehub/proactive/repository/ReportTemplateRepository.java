package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import com.smartfirehub.proactive.util.ProactiveTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리포트 양식 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V99 로 {@code report_template} 에 RLS 정책이 걸렸는데, 이 저장소의
 * 유일한 배경 호출자인 {@code ProactiveJobAsyncRunner.executeJob} 에는 {@code @Async("pipelineExecutor")} 만 있고
 * 트랜잭션이 없다. RLS 격리 값은 트랜잭션-로컬 GUC 이고 그 GUC 는 {@code TenantAwareTransactionManager.doBegin} 에서만
 * 주입되므로, 트랜잭션이 없으면 예외도 로그도 없이 0행이 되어 사용자의 sections·style 없이 리포트가 생성된다. 전파는 REQUIRED 이므로 컨트롤러 경로(이미
 * 트랜잭션 안)의 동작은 불변이다.
 *
 * <p>같은 이유로 {@code AuditLogRepository} 에도 클래스 레벨 {@code @Transactional} 이 붙어 있다. (이슈 #384 최종 리뷰
 * Critical-1)
 */
@Transactional
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
  private static final Field<String> RT_STYLE =
      field(name("report_template", "style"), String.class);
  private static final Field<LocalDateTime> RT_UPDATED_AT =
      field(name("report_template", "updated_at"), LocalDateTime.class);

  /**
   * 목록 조회 전용 요약 조회 (#632).
   *
   * <p>{@code sections}/{@code style} 전체 JSONB를 읽지 않고, 섹션 개수만 DB에서 {@code jsonb_array_length()}로
   * 계산해 반환한다 — 목록 조회 payload가 템플릿 규모/섹션 수에 비례해 커지는 문제를 근본적으로 없앤다(select-then-drop이 아니라 애초에 컬럼을 안
   * 읽음). 상세 구조가 필요하면 {@link #findById(Long)}(단건 조회)을 사용한다.
   *
   * @param page 0-based 페이지 번호
   * @param size 페이지당 개수
   */
  public List<ReportTemplateSummaryResponse> findAllForUser(Long userId, int page, int size) {
    Field<Integer> sectionCount = field("jsonb_array_length({0})", Integer.class, RT_SECTIONS);
    return dsl.select(
            RT_ID, RT_NAME, RT_DESCRIPTION, sectionCount, RT_USER_ID, RT_CREATED_AT, RT_UPDATED_AT)
        .from(REPORT_TEMPLATE)
        .where(RT_USER_ID.isNull().or(RT_USER_ID.eq(userId)))
        .orderBy(RT_ID.asc())
        .limit(size)
        .offset(page * size)
        .fetch(
            r ->
                new ReportTemplateSummaryResponse(
                    r.get(RT_ID),
                    r.get(RT_NAME),
                    r.get(RT_DESCRIPTION),
                    r.get(sectionCount),
                    r.get(RT_USER_ID),
                    r.get(RT_USER_ID) == null,
                    r.get(RT_CREATED_AT),
                    r.get(RT_UPDATED_AT)));
  }

  public Optional<ReportTemplateResponse> findById(Long id) {
    return fetchOne(RT_ID.eq(id));
  }

  /**
   * 사용자가 쓸 수 있는 양식만 단건 조회한다 — 기본(빌트인, user_id NULL) 양식이거나 본인 양식일 때만 돌려준다(WD-163).
   *
   * <p>목록({@link #findAllForUser})과 같은 범위다. 상세 조회·작업 연결·작업 실행이 이 범위를 따르지 않으면 양식 번호만으로 남의 비공개 양식(섹션
   * 지시문 전체)을 읽거나 내 작업에 연결해 쓸 수 있었다.
   */
  public Optional<ReportTemplateResponse> findVisibleTo(Long id, Long userId) {
    return fetchOne(RT_ID.eq(id).and(RT_USER_ID.isNull().or(RT_USER_ID.eq(userId))));
  }

  private Optional<ReportTemplateResponse> fetchOne(Condition condition) {
    return dsl.select(
            RT_ID,
            RT_NAME,
            RT_DESCRIPTION,
            RT_SECTIONS,
            RT_STYLE,
            RT_USER_ID,
            RT_CREATED_AT,
            RT_UPDATED_AT)
        .from(REPORT_TEMPLATE)
        .where(condition)
        .fetchOptional(
            r ->
                toResponse(
                    r.get(RT_ID),
                    r.get(RT_NAME),
                    r.get(RT_DESCRIPTION),
                    r.get(RT_SECTIONS),
                    r.get(RT_STYLE),
                    r.get(RT_USER_ID),
                    r.get(RT_CREATED_AT),
                    r.get(RT_UPDATED_AT)));
  }

  public Long create(
      String name,
      String description,
      List<Map<String, Object>> sections,
      String style,
      Long userId) {
    try {
      String sectionsJson = objectMapper.writeValueAsString(sections);
      return dsl.insertInto(REPORT_TEMPLATE)
          .set(RT_NAME, name)
          .set(RT_DESCRIPTION, blankToNull(description))
          .set(RT_SECTIONS, JSONB.valueOf(sectionsJson))
          .set(RT_STYLE, blankToNull(style))
          .set(RT_USER_ID, userId)
          .returning(RT_ID)
          .fetchOne(r -> r.get(RT_ID));
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize sections", e);
    }
  }

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   *
   * <p>행이 없으면 빈 값, 행은 있는데 수정 시각이 비어 있으면(nullable 컬럼, V42) 값이 null 인 레코드다 — 둘을 구분해야 수정 시각이 빈 옛 행을
   * "없음"으로 잘못 거부하지 않는다.
   */
  public Optional<Record1<LocalDateTime>> lockUpdatedAtById(Long id) {
    return dsl.select(RT_UPDATED_AT)
        .from(REPORT_TEMPLATE)
        .where(RT_ID.eq(id))
        .forUpdate()
        .fetchOptional();
  }

  /**
   * 양식을 고치고 새로 저장된 수정 시각을 돌려준다(WD-364).
   *
   * <p>Java 값이 아니라 DB 에 저장된 값(RETURNING)을 돌려준다 — PG timestamp 는 마이크로초로 반올림하므로 넣은 값을 그대로 돌려주면 다음 저장의
   * 기준 버전이 저장값과 어긋나 자기 저장과 충돌한다. 고친 행이 없으면(남의 양식·빌트인) null.
   */
  public LocalDateTime update(
      Long id,
      Long userId,
      String name,
      String description,
      List<Map<String, Object>> sections,
      String style) {
    // 빌트인 템플릿(user_id IS NULL)은 수정 불가
    // null 은 "바꾸지 않음"(AI 도구 update_report_template 가 일부 필드만 보낸다), 빈 문자열은 "비움"이다 (WD-135).
    // 예전에는 화면이 비운 값을 아예 빼고 보내 성공 토스트만 뜨고 옛 값이 남았다 — 화면은 이제 빈 문자열을 보낸다.
    try {
      var query = dsl.update(REPORT_TEMPLATE);
      var step = query.set(RT_UPDATED_AT, ProactiveTime.nowUtc());
      if (name != null) step = step.set(RT_NAME, name);
      if (description != null) step = step.set(RT_DESCRIPTION, blankToNull(description));
      if (sections != null) {
        String sectionsJson = objectMapper.writeValueAsString(sections);
        step = step.set(RT_SECTIONS, JSONB.valueOf(sectionsJson));
      }
      if (style != null) step = step.set(RT_STYLE, blankToNull(style));
      return step.where(RT_ID.eq(id).and(RT_USER_ID.eq(userId)))
          .returning(RT_UPDATED_AT)
          .fetchOptional(RT_UPDATED_AT)
          .orElse(null);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize sections", e);
    }
  }

  /**
   * 공백뿐인 설명·작성 스타일은 NULL 로 저장한다 (WD-135).
   *
   * <p>비운 값을 빈 문자열로 남기면 "값 없음"이 NULL·"" 두 가지가 되고, 실행기는 {@code style != null} 만 보고 빈 지시문을 AI 에 넘긴다.
   * 저장 시점에 한 가지(NULL)로 맞춘다.
   */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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
      String style,
      Long userId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    try {
      List<Map<String, Object>> sectionList =
          sections != null
              ? objectMapper.readValue(sections.data(), new TypeReference<>() {})
              : List.of();
      return new ReportTemplateResponse(
          id, name, description, sectionList, style, userId, userId == null, createdAt, updatedAt);
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize sections", e);
    }
  }
}

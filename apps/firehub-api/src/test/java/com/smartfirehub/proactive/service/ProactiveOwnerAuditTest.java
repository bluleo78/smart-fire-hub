package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작성자 본인의 스마트 작업·리포트 양식 변경 감사(WD-464) 통합 테스트.
 *
 * <p>검증: 생성·수정·삭제·켜기/끄기가 audit_log 에 한 행씩 남고(resource 는 관리자 감사와 같은 proactive_job, 양식은
 * report_template), 변경 없는 저장·이미 꺼진 작업 끄기와 거부된 요청(400·404·409 편집 충돌)은 아무것도 남기지 않으며,
 * 프롬프트·설정(채널·수신자)·섹션 내용·스타일 값이 설명·metadata 에 새지 않는다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 서비스 트랜잭션의 실제 커밋·롤백으로 감사가 남거나 남지 않는지 본다. 작업은 cron 없이
 * 만들어 스케줄러에 등록되지 않게 한다.
 */
class ProactiveOwnerAuditTest extends IntegrationTestBase {

  /** 값 누출 검사용 표식 — 프롬프트·설정·섹션·스타일에 넣고 감사 설명·metadata 에 없어야 한다. */
  private static final String MARKER = "MARKER_WD464_SECRET";

  private static final LocalDateTime STALE = LocalDateTime.of(2000, 1, 1, 0, 0);

  @Autowired private ProactiveJobService jobService;
  @Autowired private ReportTemplateService templateService;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  private final List<Long> users = new ArrayList<>();
  private Long owner;
  private Long other;

  /** 감사 행 한 줄(검증용 투영). */
  private record Audit(
      String action, String resource, String resourceId, String description, JsonNode metadata) {}

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd464_job_owner");
    other = TenantRlsTestSupport.insertUser(dsl, "wd464_job_other");
    users.add(owner);
    users.add(other);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 감사 행은 user_id FK 라 사용자보다 먼저 지운다.
    inTenantFixture(
        () -> {
          for (Long u : users) {
            dsl.execute("DELETE FROM audit_log WHERE user_id = ?", u);
            dsl.execute("DELETE FROM proactive_job WHERE user_id = ?", u);
            dsl.execute("DELETE FROM report_template WHERE user_id = ?", u);
          }
        });
    users.forEach(u -> TenantRlsTestSupport.deleteUser(dsl, u));
    users.clear();
  }

  // ── 도우미 ────────────────────────────────────────────────────────────────

  private List<Audit> audits(Long userId, String resource) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT action_type, resource, resource_id, description, metadata::text AS m"
                        + " FROM audit_log WHERE user_id = ? AND resource = ? ORDER BY id",
                    userId,
                    resource)
                .map(
                    r ->
                        new Audit(
                            r.get(0, String.class),
                            r.get(1, String.class),
                            r.get(2, String.class),
                            r.get(3, String.class),
                            readJson(r.get(4, String.class)))));
  }

  private JsonNode readJson(String json) {
    try {
      return json == null ? null : objectMapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void assertNoLeak(List<Audit> rows) {
    for (Audit a : rows) {
      assertThat(a.description()).doesNotContain(MARKER);
      assertThat(String.valueOf(a.metadata())).doesNotContain(MARKER);
    }
  }

  private static List<String> changedFields(Audit a) {
    List<String> out = new ArrayList<>();
    a.metadata().get("changedFields").forEach(n -> out.add(n.asText()));
    return out;
  }

  /** 프롬프트·설정(채널·수신자)에 표식을 넣은 작업 — cron 없이 만들어 예약되지 않는다. */
  private ProactiveJobResponse createJob(String name, boolean enabled) {
    return jobService.createJob(
        new CreateProactiveJobRequest(
            name,
            MARKER + " 프롬프트",
            null,
            null,
            null,
            enabled,
            null,
            Map.of("channels", List.of("CHAT"), "recipientEmails", List.of(MARKER + "@x.com"))),
        owner);
  }

  /** 변경할 항목만 채운 작업 수정 요청(나머지 null = 그대로). */
  private static UpdateProactiveJobRequest jobUpdate(
      String name, String prompt, Boolean enabled, Map<String, Object> config) {
    return new UpdateProactiveJobRequest(name, prompt, null, null, null, enabled, null, config);
  }

  private static List<Map<String, Object>> sections(String content) {
    return List.of(Map.of("key", "summary", "label", "요약", "type", "text", "instruction", content));
  }

  // ── 스마트 작업 ───────────────────────────────────────────────────────────

  @Test
  void job_createUpdateDelete_recordedWithoutPromptOrConfigValues() {
    ProactiveJobResponse job = createJob("감사 작업", false);
    jobService.updateJob(
        job.id(),
        jobUpdate(
            null,
            MARKER + " 새 프롬프트",
            null,
            Map.of("channels", List.of("CHAT", "EMAIL"), "recipientEmails", List.of(MARKER))),
        owner);
    jobService.updateJob(job.id(), jobUpdate("감사 작업2", null, null, null), owner);
    jobService.deleteJob(job.id(), owner);

    List<Audit> rows = audits(owner, "proactive_job");
    assertThat(rows)
        .extracting(Audit::action)
        .containsExactly("CREATE", "UPDATE", "UPDATE", "DELETE");
    assertThat(rows).extracting(Audit::resourceId).containsOnly(String.valueOf(job.id()));
    assertThat(rows.get(0).description()).isEqualTo("스마트 작업 생성: 감사 작업");
    assertThat(rows.get(0).metadata().get("enabled").asBoolean()).isFalse();
    assertThat(rows.get(1).description()).isEqualTo("스마트 작업 수정: 감사 작업 (바뀐 항목: 프롬프트·전달·탐지 설정)");
    assertThat(changedFields(rows.get(1))).containsExactly("prompt", "config");
    assertThat(rows.get(2).description()).isEqualTo("스마트 작업 수정: 감사 작업2 (바뀐 항목: 이름)");
    assertThat(rows.get(3).description()).isEqualTo("스마트 작업 삭제: 감사 작업2");
    assertNoLeak(rows);
  }

  @Test
  void job_enableDisableToggle_recordsStatusChange_onlyWhenStateChanges() {
    ProactiveJobResponse job = createJob("토글 작업", false);
    // 목록의 켜기 토글 — enabled 만 보내는 PUT
    jobService.updateJob(job.id(), jobUpdate(null, null, true, null), owner);
    // 같은 상태로 다시 — 남지 않는다
    jobService.updateJob(job.id(), jobUpdate(null, null, true, null), owner);
    // 읽기 권한만으로 하는 내 작업 끄기(WD-192)
    jobService.disableOwnJob(job.id(), owner);
    // 이미 꺼진 작업 끄기 — 무동작이라 남지 않는다
    jobService.disableOwnJob(job.id(), owner);
    // 이름과 켜기를 한 번에 — UPDATE(이름) + STATUS_CHANGE(켜기)
    jobService.updateJob(job.id(), jobUpdate("토글 작업2", null, true, null), owner);

    List<Audit> rows = audits(owner, "proactive_job");
    assertThat(rows)
        .extracting(Audit::action)
        .containsExactly("CREATE", "STATUS_CHANGE", "STATUS_CHANGE", "UPDATE", "STATUS_CHANGE");
    assertThat(rows.get(1).description()).isEqualTo("스마트 작업 켜기: 토글 작업");
    assertThat(changedFields(rows.get(1))).containsExactly("enabled");
    assertThat(rows.get(1).metadata().get("enabled").asBoolean()).isTrue();
    assertThat(rows.get(2).description()).isEqualTo("스마트 작업 끄기: 토글 작업");
    assertThat(rows.get(2).metadata().get("enabled").asBoolean()).isFalse();
    assertThat(rows.get(3).description()).isEqualTo("스마트 작업 수정: 토글 작업2 (바뀐 항목: 이름)");
    assertThat(rows.get(4).description()).isEqualTo("스마트 작업 켜기: 토글 작업2");
  }

  @Test
  void job_saveWithSameValues_writesNothing() {
    ProactiveJobResponse job = createJob("그대로 작업", false);
    jobService.updateJob(
        job.id(),
        new UpdateProactiveJobRequest(
            job.name(),
            job.prompt(),
            null,
            null,
            null,
            job.enabled(),
            job.triggerType(),
            job.config()),
        owner);

    assertThat(audits(owner, "proactive_job")).extracting(Audit::action).containsExactly("CREATE");
  }

  @Test
  void job_rejectedRequests_writeNothing() {
    ProactiveJobResponse job = createJob("거부 작업", false);

    // 409 편집 충돌(WD-364)
    assertThatThrownBy(
            () ->
                jobService.updateJob(
                    job.id(),
                    new UpdateProactiveJobRequest(
                        "바뀌면 안 됨", null, null, null, null, true, null, null, null, STALE),
                    owner))
        .isInstanceOf(EditConflictException.class);
    // 400 — 잘못된 cron
    assertThatThrownBy(
            () ->
                jobService.updateJob(
                    job.id(),
                    new UpdateProactiveJobRequest(
                        "바뀌면 안 됨", null, null, "not a cron", null, null, null, null),
                    owner))
        .isInstanceOf(ProactiveJobException.class);
    // 404 — 남의 작업 수정·끄기·삭제
    assertThatThrownBy(
            () -> jobService.updateJob(job.id(), jobUpdate("남", null, null, null), other))
        .isInstanceOf(ProactiveJobNotFoundException.class);
    assertThatThrownBy(() -> jobService.disableOwnJob(job.id(), other))
        .isInstanceOf(ProactiveJobNotFoundException.class);
    assertThatThrownBy(() -> jobService.deleteJob(job.id(), other))
        .isInstanceOf(ProactiveJobNotFoundException.class);

    assertThat(audits(owner, "proactive_job")).extracting(Audit::action).containsExactly("CREATE");
    assertThat(audits(other, "proactive_job")).isEmpty();
  }

  // ── 리포트 양식 ───────────────────────────────────────────────────────────

  @Test
  void template_createUpdateDelete_recordedWithoutSectionOrStyleValues() {
    ReportTemplateResponse t =
        templateService.createTemplate(
            new CreateReportTemplateRequest(
                "감사 양식", MARKER + " 설명", sections(MARKER + " 지시"), MARKER + " 스타일"),
            owner);
    templateService.updateTemplate(
        t.id(),
        new UpdateReportTemplateRequest(
            "감사 양식", t.description(), sections(MARKER + " 새 지시"), MARKER + " 새 스타일"),
        owner);
    // 같은 값 재저장 — 남지 않는다
    templateService.updateTemplate(
        t.id(),
        new UpdateReportTemplateRequest(
            "감사 양식", t.description(), sections(MARKER + " 새 지시"), MARKER + " 새 스타일"),
        owner);
    templateService.deleteTemplate(t.id(), owner);

    List<Audit> rows = audits(owner, "report_template");
    assertThat(rows).extracting(Audit::action).containsExactly("CREATE", "UPDATE", "DELETE");
    assertThat(rows).extracting(Audit::resourceId).containsOnly(String.valueOf(t.id()));
    assertThat(rows.get(0).description()).isEqualTo("리포트 양식 생성: 감사 양식");
    assertThat(rows.get(1).description()).isEqualTo("리포트 양식 수정: 감사 양식 (바뀐 항목: 섹션·스타일)");
    assertThat(changedFields(rows.get(1))).containsExactly("sections", "style");
    assertThat(rows.get(2).description()).isEqualTo("리포트 양식 삭제: 감사 양식");
    assertNoLeak(rows);
  }

  @Test
  void template_rejectedRequests_writeNothing() {
    ReportTemplateResponse t =
        templateService.createTemplate(
            new CreateReportTemplateRequest("거부 양식", null, sections("지시"), null), owner);

    // 409 편집 충돌(WD-364)
    assertThatThrownBy(
            () ->
                templateService.updateTemplate(
                    t.id(),
                    new UpdateReportTemplateRequest("바뀌면 안 됨", null, sections("x"), null, STALE),
                    owner))
        .isInstanceOf(EditConflictException.class);
    // 404 — 남의 양식
    assertThatThrownBy(() -> templateService.deleteTemplate(t.id(), other))
        .isInstanceOf(ProactiveJobNotFoundException.class);
    // 400 — 빌트인 양식은 고칠 수 없다
    Long builtinId =
        inTenantFixture(
            () ->
                dsl.fetchOne("SELECT id FROM report_template WHERE user_id IS NULL LIMIT 1")
                    .get(0, Long.class));
    assertThatThrownBy(
            () ->
                templateService.updateTemplate(
                    builtinId,
                    new UpdateReportTemplateRequest("빌트인", null, sections("x"), null),
                    owner))
        .isInstanceOf(ProactiveJobException.class);

    assertThat(audits(owner, "report_template"))
        .extracting(Audit::action)
        .containsExactly("CREATE");
    assertThat(audits(other, "report_template")).isEmpty();
  }
}

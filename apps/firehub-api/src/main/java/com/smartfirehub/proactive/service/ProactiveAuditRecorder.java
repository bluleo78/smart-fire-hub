package com.smartfirehub.proactive.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 작성자 본인의 스마트 작업·리포트 양식 변경 감사 기록(WD-464). ProactiveJobService·ReportTemplateService 가
 * 생성·수정·삭제·켜기/끄기 성공 경로 끝에서 부른다.
 *
 * <p><b>resource</b>: 스마트 작업은 {@link ProactiveJobAdminService#AUDIT_RESOURCE}({@code
 * proactive_job}) — 관리자 끄기·삭제 감사와 같은 값이라 리소스 필터 하나로 작성자 행위와 관리 행위가 함께 보인다. 리포트 양식은 테이블 이름 {@code
 * report_template} (같은 관례). resourceId 는 대상 id.
 *
 * <p><b>action</b>: 기존 값만 쓴다 — CREATE·UPDATE·DELETE, 켜기/끄기는 STATUS_CHANGE(트리거 켜기/끄기와 같은 분류). 켜기와 다른
 * 항목을 한 번에 저장하면 UPDATE 1행(켜기 외 바뀐 항목) + STATUS_CHANGE 1행(켜기/끄기)으로 나눠 남긴다.
 *
 * <p><b>남기지 않는 것</b>: 감사 화면은 audit:read 만 있으면 볼 수 있다. 설명·metadata 에는 대상(id·이름)·변경 종류·바뀐 항목의
 * <b>이름</b>만 싣고 프롬프트·cron·전달 채널·수신자·메트릭 SQL·양식 섹션 내용·스타일은 싣지 않는다.
 *
 * <p>호출 트랜잭션 안에서 쓰므로 이후 같은 트랜잭션에서 예외가 나면 감사 행도 함께 롤백된다 — 거부된 요청(400/403/404/409)은 기록이 남지 않는다. 행위자
 * username 은 감사 테이블 NOT NULL 이라 조회해서 넣는다(없으면 "unknown", 행위자 없는 내부 경로는 "system"). IP·User-Agent 는
 * 남기지 않는다(null).
 */
@Component
@RequiredArgsConstructor
public class ProactiveAuditRecorder {

  /** 스마트 작업 감사 resource — 관리자 끄기·삭제 감사와 같은 값. */
  public static final String RESOURCE_JOB = ProactiveJobAdminService.AUDIT_RESOURCE;

  /** 리포트 양식 감사 resource — 테이블 이름(AnalyticsAssetKind.table()·proactive_job 과 같은 관례). */
  public static final String RESOURCE_TEMPLATE = "report_template";

  /** 켜기/끄기 감사의 changedFields 값. */
  public static final String FIELD_ENABLED = "enabled";

  /** 설명 문구의 대상 이름 — 스마트 작업. */
  public static final String LABEL_JOB = "스마트 작업";

  /** 설명 문구의 대상 이름 — 리포트 양식. */
  public static final String LABEL_TEMPLATE = "리포트 양식";

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /**
   * 생성 감사 — 「스마트 작업 생성: 이름」.
   *
   * @param extra 종류별 추가 항목(켜짐 여부 등 값이 아닌 상태만) — null 가능
   */
  public void created(
      Long userId, String resource, String label, Long id, String name, Map<String, Object> extra) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    if (extra != null) {
      metadata.putAll(extra);
    }
    record(userId, "CREATE", resource, id, label + " 생성: " + name, metadata);
  }

  /** 수정 감사 — 「리포트 양식 수정: 이름 (바뀐 항목: 이름·섹션)」. 바뀐 항목이 없으면 남기지 않는다. */
  public void updated(
      Long userId,
      String resource,
      String label,
      Long id,
      String name,
      List<String> changedFields,
      Map<String, String> labels) {
    if (changedFields.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("changedFields", List.copyOf(changedFields));
    record(
        userId,
        "UPDATE",
        resource,
        id,
        label + " 수정: " + name + " (바뀐 항목: " + changedLabels(changedFields, labels) + ")",
        metadata);
  }

  /** 스마트 작업 켜기/끄기 감사 — 「스마트 작업 끄기: 이름」. */
  public void jobEnabledChanged(Long userId, Long jobId, String name, boolean enabled) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("changedFields", List.of(FIELD_ENABLED));
    metadata.put("enabled", enabled);
    record(
        userId,
        "STATUS_CHANGE",
        RESOURCE_JOB,
        jobId,
        LABEL_JOB + (enabled ? " 켜기: " : " 끄기: ") + name,
        metadata);
  }

  /** 삭제 감사 — 「리포트 양식 삭제: 이름」. 행이 사라지므로 이름은 지우기 전에 읽은 값을 받는다. */
  public void deleted(Long userId, String resource, String label, Long id, String name) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    record(userId, "DELETE", resource, id, label + " 삭제: " + name, metadata);
  }

  /** 감사 행 한 줄. */
  private void record(
      Long userId,
      String action,
      String resource,
      Long id,
      String description,
      Map<String, Object> metadata) {
    String actorName =
        userId == null
            ? "system"
            : userRepository.findById(userId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        userId,
        actorName,
        action,
        resource,
        String.valueOf(id),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }

  /** 바뀐 항목 키를 설명용 한국어 라벨로 잇는다(예: "이름·프롬프트"). 라벨이 없는 키는 키 그대로 쓴다. */
  static String changedLabels(List<String> changedFields, Map<String, String> labels) {
    return String.join("·", changedFields.stream().map(f -> labels.getOrDefault(f, f)).toList());
  }
}

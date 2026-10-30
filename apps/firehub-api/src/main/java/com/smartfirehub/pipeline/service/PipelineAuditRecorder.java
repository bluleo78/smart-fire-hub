package com.smartfirehub.pipeline.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 파이프라인·트리거 변경 감사 기록(WD-462). PipelineService·TriggerService 가 생성·수정·삭제·켜기/끄기 성공 경로 끝에서 부른다.
 *
 * <p>행위자 username 은 감사 테이블 NOT NULL 이라 조회해서 넣는다 — RoleService·ApiConnectionService 의 audit 와 같은
 * 방식이다. 행위자가 없는 내부 경로(사용자 요청이 아닌 호출)는 ApiConnectionNotifier 의 주기 점검처럼 "system" 으로 남긴다.
 * IP·User-Agent 는 다른 도메인 변경 감사와 마찬가지로 남기지 않는다(null).
 *
 * <p>호출 트랜잭션 안에서 쓰므로 audit_log.tenant_id 는 GUC 기본값, 즉 현재 워크스페이스로 기록되고, 이후 같은 트랜잭션에서 예외가 나면 감사 행도 함께
 * 롤백된다 — 거부된 변경은 기록이 남지 않는다.
 *
 * <p>설명·metadata 에는 대상(id·이름)·유형·바뀐 항목의 <b>이름</b>만 싣는다. 스텝 SQL·Python 코드, API 설정, 트리거 config 값(웹훅
 * 시크릿·API 토큰 해시 포함)은 싣지 않는다 — 감사 화면은 audit:read 만 있으면 볼 수 있다.
 */
@Component
@RequiredArgsConstructor
public class PipelineAuditRecorder {

  /** 파이프라인 감사 resource — 실행·취소 감사(PipelineExecutionService)와 같은 값. */
  public static final String RESOURCE_PIPELINE = "pipeline";

  /**
   * 트리거 감사 resource — 테이블 이름(pipeline_trigger)을 쓴다(api_connection·AnalyticsAssetKind.table() 과 같은
   * 관례).
   */
  public static final String RESOURCE_TRIGGER = "pipeline_trigger";

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /**
   * 성공한 변경을 감사 로그에 남긴다.
   *
   * @param userId 행위자(null 이면 내부 경로 — username "system")
   * @param action CREATE·UPDATE·DELETE·STATUS_CHANGE
   * @param resource {@link #RESOURCE_PIPELINE} 또는 {@link #RESOURCE_TRIGGER}
   * @param resourceId 대상 id
   */
  public void record(
      Long userId,
      String action,
      String resource,
      Long resourceId,
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
        String.valueOf(resourceId),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }

  /**
   * 바뀐 항목 키를 설명용 한국어 라벨로 잇는다(예: "이름·스텝"). 라벨이 없는 키는 키 그대로 쓴다 — 새 config 키가 생겨도 설명이 비지 않게.
   * ApiConnectionService 수정 감사의 「(바뀐 항목: …)」 문구와 같은 형식이다.
   */
  public static String changedLabels(List<String> changedFields, Map<String, String> labels) {
    return String.join("·", changedFields.stream().map(f -> labels.getOrDefault(f, f)).toList());
  }
}

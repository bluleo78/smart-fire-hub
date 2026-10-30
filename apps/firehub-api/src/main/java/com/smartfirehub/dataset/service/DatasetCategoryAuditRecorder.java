package com.smartfirehub.dataset.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 데이터셋 카테고리 생성·수정·삭제 감사 기록(WD-464). DatasetCategoryService 가 성공 경로 끝에서 부른다.
 *
 * <p><b>resource</b>: 테이블 이름 {@code dataset_category}(AnalyticsAssetKind.table()·proactive_job 과 같은
 * 관례). {@code dataset} 을 쓰지 않는 이유: 감사 목록의 열람 필터(WD-289)와 홈 활동 피드가 resource=dataset 행의 resource_id 를
 * dataset.id 로 맞춰 보므로, 카테고리 id 가 데이터셋 id 로 읽혀 엉뚱한 데이터셋의 가시성 판정·「데이터셋 생성됨」 피드를 탄다.
 *
 * <p><b>action</b>: 기존 값 CREATE·UPDATE·DELETE. <b>남기지 않는 것</b>: 감사 화면은 audit:read 만 있으면 볼 수 있으므로 설명
 * 본문은 싣지 않고 대상(id·이름)·바뀐 항목의 <b>이름</b>만 싣는다.
 *
 * <p>호출 트랜잭션 안에서 쓰므로 이후 예외가 나면 감사 행도 함께 롤백된다 — 거부된 요청(400·404·409)은 기록이 남지 않는다. 행위자 username 은 감사
 * 테이블 NOT NULL 이라 조회해서 넣는다(없으면 "unknown", 행위자 없는 내부 경로는 "system"). IP·User-Agent 는 남기지 않는다(null).
 */
@Component
@RequiredArgsConstructor
public class DatasetCategoryAuditRecorder {

  /** 카테고리 감사 resource — 테이블 이름. */
  public static final String RESOURCE_CATEGORY = "dataset_category";

  /** 수정 감사의 바뀐 항목 키 → 설명용 라벨. */
  private static final Map<String, String> FIELD_LABELS = Map.of("name", "이름", "description", "설명");

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /** 생성 감사 — 「카테고리 생성: 이름」. */
  public void created(Long userId, Long categoryId, String name) {
    record(userId, "CREATE", categoryId, "카테고리 생성: " + name, metadata(name, null));
  }

  /** 수정 감사 — 「카테고리 수정: 이름 (바뀐 항목: 이름·설명)」. 바뀐 항목이 없으면 남기지 않는다. */
  public void updated(Long userId, Long categoryId, String name, List<String> changedFields) {
    if (changedFields.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    String labels =
        String.join("·", changedFields.stream().map(f -> FIELD_LABELS.getOrDefault(f, f)).toList());
    record(
        userId,
        "UPDATE",
        categoryId,
        "카테고리 수정: " + name + " (바뀐 항목: " + labels + ")",
        metadata(name, changedFields));
  }

  /** 삭제 감사 — 「카테고리 삭제: 이름」. 이름은 지우기 전에 읽은 값을 받는다. */
  public void deleted(Long userId, Long categoryId, String name) {
    record(userId, "DELETE", categoryId, "카테고리 삭제: " + name, metadata(name, null));
  }

  private static Map<String, Object> metadata(String name, List<String> changedFields) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    if (changedFields != null) {
      metadata.put("changedFields", List.copyOf(changedFields));
    }
    return metadata;
  }

  /** 감사 행 한 줄. */
  private void record(
      Long userId,
      String action,
      Long categoryId,
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
        RESOURCE_CATEGORY,
        String.valueOf(categoryId),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }
}

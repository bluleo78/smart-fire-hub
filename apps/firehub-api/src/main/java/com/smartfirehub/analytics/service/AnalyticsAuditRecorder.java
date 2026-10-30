package com.smartfirehub.analytics.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 작성자 본인의 저장 쿼리·차트·대시보드 변경 감사 기록(WD-464). SavedQueryService·ChartService·AnalyticsDashboardService
 * 가 생성·수정·삭제·공유 켜기/끄기 성공 경로 끝에서 부른다.
 *
 * <p><b>resource</b>: {@link AnalyticsAssetKind#table()}(dashboard·chart·saved_query) — 관리자 공유
 * 해제·삭제 감사(AnalyticsAdminService)와 같은 값이라 감사 화면의 리소스 필터 하나로 작성자 행위와 관리 행위가 함께 보인다. resourceId 는 자산
 * id.
 *
 * <p><b>action</b>: 기존 값만 쓴다 — CREATE·UPDATE·DELETE, 공유 켜기/끄기는 STATUS_CHANGE(트리거·스마트 작업 켜기/끄기와 같은
 * 「토글」 분류). 공유와 다른 항목을 한 번에 저장하면 UPDATE 1행(공유 외 바뀐 항목) + STATUS_CHANGE 1행(공유)으로 나눠 남긴다 — 「공유 켜기」
 * 문구와 「수정(바뀐 항목)」 문구를 한 행에 담을 수 없고, 상태 변경 필터로 공유 변경을 빠짐없이 찾게 하려는 것이다.
 *
 * <p><b>남기지 않는 것</b>: 감사 화면은 audit:read 만 있으면 볼 수 있다. 설명·metadata 에는 대상(id·이름)·변경 종류·바뀐 항목의
 * <b>이름</b>만 싣고 SQL 본문·설명 본문·차트 설정 값·폴더 이름·연결 데이터셋은 싣지 않는다.
 *
 * <p>호출 트랜잭션 안에서 쓰므로 이후 같은 트랜잭션에서 예외가 나면 감사 행도 함께 롤백된다 — 거부된 요청(400/403/404/409)은 기록이 남지 않는다. 행위자
 * username 은 감사 테이블 NOT NULL 이라 조회해서 넣는다(없으면 "unknown", 행위자 없는 내부 경로는 "system"). IP·User-Agent 는 다른
 * 도메인 변경 감사처럼 남기지 않는다(null).
 */
@Component
@RequiredArgsConstructor
public class AnalyticsAuditRecorder {

  /** 공유 켜기/끄기 감사의 changedFields 값 — 수정 감사의 공유 외 항목과 구분한다. */
  public static final String FIELD_IS_SHARED = "isShared";

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /**
   * 생성 감사 — 「저장 쿼리 생성: 이름」.
   *
   * @param extra 종류별 추가 항목(복제 원본 id 등, 값이 아닌 식별자만) — null 가능. 복제는 설명 대신 metadata.clonedFrom 으로 구분한다
   *     (복제본 이름에 이미 「(복사본)」이 붙는다).
   */
  public void created(
      Long userId,
      AnalyticsAssetKind kind,
      Long id,
      String name,
      boolean isShared,
      Map<String, Object> extra) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("isShared", isShared);
    if (extra != null) {
      metadata.putAll(extra);
    }
    record(userId, "CREATE", kind, id, kind.label() + " 생성: " + name, metadata);
  }

  /**
   * 수정 감사 — 「차트 수정: 이름 (바뀐 항목: 이름·설명)」. 바뀐 항목이 없으면 남기지 않는다.
   *
   * @param changedFields 공유 외 바뀐 항목 키(저장 전후 DB 값을 비교해 모은 것)
   * @param labels 항목 키 → 설명용 한국어 라벨
   */
  public void updated(
      Long userId,
      AnalyticsAssetKind kind,
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
        kind,
        id,
        kind.label() + " 수정: " + name + " (바뀐 항목: " + changedLabels(changedFields, labels) + ")",
        metadata);
  }

  /**
   * 공유 켜기/끄기 감사 — 「대시보드 공유 켜기: 이름」.
   *
   * @param viaDashboardId 대시보드 공유에 딸려 함께 공유된 차트·쿼리면 그 대시보드 id(WD-194), 직접 바꾼 것이면 null
   * @param viaDashboardName 위 대시보드 이름(설명용) — viaDashboardId 가 null 이면 무시
   */
  public void shareChanged(
      Long userId,
      AnalyticsAssetKind kind,
      Long id,
      String name,
      boolean isShared,
      Long viaDashboardId,
      String viaDashboardName) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("changedFields", List.of(FIELD_IS_SHARED));
    metadata.put("isShared", isShared);
    String description = kind.label() + (isShared ? " 공유 켜기: " : " 공유 끄기: ") + name;
    if (viaDashboardId != null) {
      metadata.put("viaDashboardId", viaDashboardId);
      description += " (대시보드 공유에 따라: " + viaDashboardName + ")";
    }
    record(userId, "STATUS_CHANGE", kind, id, description, metadata);
  }

  /** 삭제 감사 — 「대시보드 삭제: 이름」. 행이 사라지므로 이름·공유 여부는 지우기 전에 읽은 값을 받는다. */
  public void deleted(
      Long userId, AnalyticsAssetKind kind, Long id, String name, boolean wasShared) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("wasShared", wasShared);
    record(userId, "DELETE", kind, id, kind.label() + " 삭제: " + name, metadata);
  }

  /** 감사 행 한 줄 — resource 는 관리자 감사와 같은 {@link AnalyticsAssetKind#table()}. */
  private void record(
      Long userId,
      String action,
      AnalyticsAssetKind kind,
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
        kind.table(),
        String.valueOf(id),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }

  /** 바뀐 항목 키를 설명용 한국어 라벨로 잇는다(예: "이름·설명"). 라벨이 없는 키는 키 그대로 쓴다. */
  static String changedLabels(List<String> changedFields, Map<String, String> labels) {
    return String.join("·", changedFields.stream().map(f -> labels.getOrDefault(f, f)).toList());
  }
}

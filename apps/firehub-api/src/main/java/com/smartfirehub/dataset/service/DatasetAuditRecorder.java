package com.smartfirehub.dataset.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 데이터셋 변경 감사 기록(WD-463). 데이터셋 정보 수정·상태 변경·필드 추가/수정/삭제·태그 추가/제거·데이터 탭 행 추가/수정/일괄 삭제의 성공 경로 끝에서
 * DatasetService·DatasetTagService·DatasetDataService 가 부른다.
 *
 * <p><b>resource·resourceId</b>: 모든 행이 resource = {@code dataset}, resourceId = 데이터셋 id 다. 감사 목록의
 * 열람 필터(WD-289)가 resource_id 를 dataset.id 와 맞춰 볼 수 없는 데이터셋의 행을 빼므로, 필드·행·태그 id 를 resourceId 로 쓰거나 새
 * resource 값을 만들면 그 필터가 어긋나거나 빠진다. 무엇을 바꿨는지는 metadata.{@code change}(예: FIELD_ADD)와 설명으로 구분한다.
 *
 * <p><b>action</b>: 기존 값만 쓴다 — 데이터셋 정보·필드·태그·행의 추가와 수정은 UPDATE, 데이터가 사라지는 필드 삭제·행 일괄 삭제는 DELETE, 상태
 * 변경은 STATUS_CHANGE. CREATE 는 쓰지 않는다: 홈 대시보드 활동 피드(DashboardService)가 CREATE + dataset 행을 모두 「데이터셋
 * 생성됨」으로 보여 주므로, 필드·행 추가가 데이터셋 생성으로 둔갑한다.
 *
 * <p><b>남기지 않는 것</b>: 감사 화면은 audit:read 만 있으면 볼 수 있다. 그래서 설명·metadata 에는 대상(데이터셋 id·이름, 필드 이름, 태그
 * 이름)·변경 종류·바뀐 항목의 <b>이름</b>만 싣는다. 데이터셋 설명 본문·상태 메모·필드 설명·행 값은 싣지 않는다. 행은 내부 행 id(PK 컬럼 값이 아닌
 * data.&lt;table&gt;.id)만 앞의 몇 개 싣는다 — PK 값은 실제 데이터일 수 있다.
 *
 * <p>호출 트랜잭션 안에서 쓰므로 이후 같은 트랜잭션에서 예외가 나면 감사 행도 함께 롤백된다 — 거부된 요청은 기록이 남지 않는다. 행위자 username 은 감사 테이블
 * NOT NULL 이라 조회해서 넣는다(없으면 "unknown", 행위자 없는 내부·테스트 경로는 "system"). IP·User-Agent 는 다른 도메인 변경 감사처럼
 * 남기지 않는다(null).
 */
@Component
@RequiredArgsConstructor
public class DatasetAuditRecorder {

  /** 데이터셋 감사 resource — 생성·삭제 감사(DatasetService)와 같은 값. */
  public static final String RESOURCE_DATASET = "dataset";

  /** 행 감사 metadata 에 싣는 행 id 상한 — 일괄 삭제가 1000건이어도 감사 행이 커지지 않게 앞의 몇 개만 남긴다. */
  public static final int MAX_ROW_IDS = 5;

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /**
   * 성공한 데이터셋 변경을 감사 로그에 남긴다.
   *
   * @param userId 행위자(null 이면 사용자 요청이 아닌 내부·테스트 경로 — username "system")
   * @param action UPDATE·DELETE·STATUS_CHANGE
   * @param datasetId 대상 데이터셋 id(resourceId)
   * @param datasetName 대상 데이터셋 이름 — metadata.datasetName 으로 싣는다
   * @param change 변경 종류(metadata.change, 예: FIELD_ADD)
   * @param description 한국어 설명
   * @param extra 변경 종류별 추가 항목(이름·개수·id 만) — null 가능
   */
  public void record(
      Long userId,
      String action,
      Long datasetId,
      String datasetName,
      String change,
      String description,
      Map<String, Object> extra) {
    String actorName =
        userId == null
            ? "system"
            : userRepository.findById(userId).map(UserResponse::username).orElse("unknown");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("change", change);
    metadata.put("datasetName", datasetName);
    if (extra != null) {
      metadata.putAll(extra);
    }
    auditLogService.log(
        userId,
        actorName,
        action,
        RESOURCE_DATASET,
        String.valueOf(datasetId),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }

  /**
   * 바뀐 항목 키를 설명용 한국어 라벨로 잇는다(예: "이름·설명"). 라벨이 없는 키는 키 그대로 쓴다 — 행 수정처럼 키가 컬럼 이름이면 컬럼 이름이 그대로 보인다.
   */
  public static String changedLabels(List<String> changedFields, Map<String, String> labels) {
    return String.join("·", changedFields.stream().map(f -> labels.getOrDefault(f, f)).toList());
  }

  /** 행 id 를 중복 없이 요청 순서대로 앞의 {@link #MAX_ROW_IDS} 개만 남긴다. */
  public static List<Long> cappedRowIds(List<Long> rowIds) {
    List<Long> capped = new ArrayList<>();
    for (Long id : new LinkedHashSet<>(rowIds)) {
      if (capped.size() >= MAX_ROW_IDS) {
        break;
      }
      capped.add(id);
    }
    return capped;
  }
}

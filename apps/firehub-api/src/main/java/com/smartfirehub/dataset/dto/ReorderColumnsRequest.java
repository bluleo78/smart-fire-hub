package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 데이터셋 필드 표시 순서 변경 요청 — 최종 순서대로 모든 필드 ID 를 담는다.
 *
 * <p>WD-604: 목록 누락·null·null 요소를 요청 경계(Bean Validation)에서 400 으로 거부한다 — 그대로 두면 서비스({@code
 * DatasetService.reorderColumns})가 null 목록으로 {@code new HashSet<>(null)} 을 만들다 NPE(500)가 났다. 빈 배열은
 * 형식상 유효하므로 여기서 막지 않고, 서비스의 「필드 목록과 일치하지 않습니다」 400 이 그대로 안내한다.
 */
public record ReorderColumnsRequest(
    @NotNull(message = ReorderColumnsRequest.COLUMN_IDS_REQUIRED_MESSAGE)
        List<@NotNull(message = ReorderColumnsRequest.COLUMN_ID_REQUIRED_MESSAGE) Long> columnIds) {

  /** 목록 누락·null 안내 — 서버에 한국어 Bean Validation 기본 문구가 없어 명시한다(WD-570 과 같은 이유). */
  public static final String COLUMN_IDS_REQUIRED_MESSAGE = "필드 순서 목록(columnIds)을 보내 주세요.";

  /** 목록 안 null 요소 안내 — errors 의 키(columnIds[n])가 위치를 짚는다. */
  public static final String COLUMN_ID_REQUIRED_MESSAGE = "필드 ID 가 비어 있습니다.";
}

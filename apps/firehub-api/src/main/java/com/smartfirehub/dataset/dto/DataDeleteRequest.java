package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 행 삭제 요청. 컨트롤러가 빈·초과 rowIds 에 본문 없는 400 을 돌려줘 사용자가 원인을 알 수 없었으므로(WD-1083), Bean Validation 으로 검증해
 * GlobalExceptionHandler 가 한국어 문구를 담은 400 을 만들게 한다 — 없음·빈·1000 초과는 {@code errors.rowIds}, 요소 null 은
 * {@code errors["rowIds[i]"]}.
 */
public record DataDeleteRequest(
    @NotNull(message = DataDeleteRequest.ROW_IDS_MESSAGE)
        @Size(min = 1, max = 1000, message = DataDeleteRequest.ROW_IDS_MESSAGE)
        List<@NotNull(message = DataDeleteRequest.NULL_ID_MESSAGE) Long> rowIds) {

  /** 개수 기준(1~1000) 검증 실패 공통 문구. */
  static final String ROW_IDS_MESSAGE = "삭제할 행을 1개 이상 1000개 이하로 선택하세요.";

  /** rowIds 요소가 null 일 때의 문구. */
  static final String NULL_ID_MESSAGE = "행 ID 는 비어 있을 수 없습니다.";
}

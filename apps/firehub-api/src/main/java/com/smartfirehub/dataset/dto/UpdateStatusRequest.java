package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.Size;

/**
 * 데이터셋 상태(인증·사용 중단) 변경 요청.
 *
 * <p>WD-381: 상태 노트는 데이터셋 상세 머리(제목 아래)에 보이는 짧은 사유라 길이 상한을 둔다. 예전에는 제한이 없어 10만 자 노트가
 * 그대로 저장돼 머리가 수만 px 로 늘어나고 탭·본문이 그 아래로 밀렸다.
 */
public record UpdateStatusRequest(
    String status,
    @Size(max = UpdateStatusRequest.NOTE_MAX_LENGTH, message = "상태 노트는 {max}자 이하여야 합니다.")
        String note) {

  /** 상태 노트 최대 길이 — 웹 입력 칸(STATUS_NOTE_MAX_LENGTH)과 같은 값. */
  public static final int NOTE_MAX_LENGTH = 500;
}

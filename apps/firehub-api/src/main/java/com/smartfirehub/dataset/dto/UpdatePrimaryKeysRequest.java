package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 데이터셋의 기본 키 컬럼 집합을 한 번에 갱신하기 위한 요청 DTO.
 *
 * <p>복합 PK 의 경우 단일 컬럼별 PUT 으로는 중간 상태가 unique 하지 않아 실패하므로, 최종 PK 컬럼 ID 목록을 한 번에 받아 트랜잭션 안에서 일괄 적용한다.
 *
 * <p>WD-604: 목록 누락·null 은 400 으로 거부한다 — 예전에는 빈 목록으로 취급돼 오타·빈 본문 하나로 모든 기본 키가 조용히 지워졌다. 기본 키 해제는 빈
 * 배열({@code []})로 명시적으로 요청한다(허용). null 요소도 「없는 필드: null」 대신 위치를 짚는 400 으로 안내한다.
 */
public record UpdatePrimaryKeysRequest(
    @NotNull(message = UpdatePrimaryKeysRequest.COLUMN_IDS_REQUIRED_MESSAGE)
        List<@NotNull(message = UpdatePrimaryKeysRequest.COLUMN_ID_REQUIRED_MESSAGE) Long>
            columnIds) {

  /** 목록 누락·null 안내 — 빈 배열이 「해제」라는 점을 함께 알려 준다. */
  public static final String COLUMN_IDS_REQUIRED_MESSAGE =
      "기본 키 필드 목록(columnIds)을 보내 주세요. 기본 키를 모두 해제하려면 빈 목록을 보내세요.";

  /** 목록 안 null 요소 안내 — errors 의 키(columnIds[n])가 위치를 짚는다. */
  public static final String COLUMN_ID_REQUIRED_MESSAGE = "필드 ID 가 비어 있습니다.";
}

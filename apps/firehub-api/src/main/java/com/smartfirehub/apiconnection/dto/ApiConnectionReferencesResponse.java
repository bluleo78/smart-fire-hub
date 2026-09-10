package com.smartfirehub.apiconnection.dto;

import java.util.List;

/**
 * API 연결을 참조하는 자원 집계 응답. 삭제 전 영향 범위 확인 용도로 사용한다.
 *
 * <p>{@code pipeline_step.api_connection_id}는 FK(ON DELETE 절 없음 → 기본 RESTRICT)로 연결되어 있어, 참조가 있는
 * 연결을 삭제하면 백엔드가 409로 거부한다. 이 엔드포인트는 그 사전 확인을 위해 실제 참조 파이프라인을 노출한다. (#605)
 */
public record ApiConnectionReferencesResponse(
    long apiConnectionId, List<ReferenceItem> pipelines, int totalCount) {

  public record ReferenceItem(long id, String name) {}
}

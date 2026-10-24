package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /execute/sql} 요청(api {@code DbExecutorClient} 가 camelCase 로 보낸다).
 *
 * @param tenantId 테넌트 id — 저장소 기술자 조회 키
 * @param query 본문 한 문장(api 가 만든 INSERT…SELECT·MERGE 또는 사용자 DML)
 * @param preStatements 선행 문장(REPLACE 의 {@code DELETE FROM "S"."T"}), 없으면 빈 목록 또는 null
 * @param params Python 실행기 요청과 모양을 맞추려고 받기만 하고 쓰지 않는다(Tibero 문장은 값이 인라인돼 온다)
 * @param timeoutSeconds 요청별 시간 제한(초, 선택). 없으면 기존 50/55초, 있으면 {@code app.load.timeout-seconds} 로 자른
 *     값
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqlExecuteRequest(
    Long tenantId,
    String query,
    List<String> preStatements,
    Map<String, Object> params,
    @JsonProperty("timeout_seconds") Integer timeoutSeconds) {

  /** params·timeout_seconds 없이 만드는 편의 생성자(코드에서 직접 요청을 만들 때). */
  public SqlExecuteRequest(Long tenantId, String query, List<String> preStatements) {
    this(tenantId, query, preStatements, null, null);
  }
}

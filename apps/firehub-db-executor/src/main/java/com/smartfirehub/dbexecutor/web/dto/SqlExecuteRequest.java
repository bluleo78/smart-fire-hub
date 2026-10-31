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
 * @param executionId 파이프라인 실행 id(선택, WD-482). 있으면 출력 잠금({@code LOCK TABLE})을 얻은 직후·비우기와 본문 전에 api 에
 *     이 실행의 중지 요청을 되묻고, 중지면 롤백한 뒤 {@code cancelled=true} 로 답한다 — 잠금 대기 중에 들어온 취소를 api 러너의 체크포인트가 볼
 *     수 없어서다(Python 실행기 WD-466 과 같은 필드). 없거나 0 이하면 되묻지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqlExecuteRequest(
    Long tenantId,
    String query,
    List<String> preStatements,
    Map<String, Object> params,
    @JsonProperty("timeout_seconds") Integer timeoutSeconds,
    Long executionId) {

  /** params·timeout_seconds·executionId 없이 만드는 편의 생성자(코드에서 직접 요청을 만들 때). */
  public SqlExecuteRequest(Long tenantId, String query, List<String> preStatements) {
    this(tenantId, query, preStatements, null, null, null);
  }

  /** executionId 없이 만드는 편의 생성자(기존 5필드 모양 — 파이프라인 실행이 아닌 요청). */
  public SqlExecuteRequest(
      Long tenantId,
      String query,
      List<String> preStatements,
      Map<String, Object> params,
      Integer timeoutSeconds) {
    this(tenantId, query, preStatements, params, timeoutSeconds, null);
  }
}

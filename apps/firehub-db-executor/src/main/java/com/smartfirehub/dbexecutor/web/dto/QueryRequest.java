package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /execute/query} 요청. Python 실행기 {@code QueryExecuteRequest} 와 같은 이름을
 * 쓴다(max_rows·read_only 는 snake_case, tenantId·readSlot 은 camelCase).
 *
 * @param tenantId 테넌트 id
 * @param query 조회(SELECT·WITH) 한 문장
 * @param maxRows 돌려줄 최대 행 수(1..10000)
 * @param readOnly 읽기 전용 조회 요청인지 — true 가 아니면(false·누락) 거부한다
 * @param readSlot 읽기 슬롯 번호 — 아직 지원하지 않아 값이 있으면 거부한다(슬롯 조회는 후속 작업)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRequest(
    Long tenantId,
    String query,
    @JsonProperty("max_rows") Integer maxRows,
    @JsonProperty("read_only") Boolean readOnly,
    Integer readSlot) {}

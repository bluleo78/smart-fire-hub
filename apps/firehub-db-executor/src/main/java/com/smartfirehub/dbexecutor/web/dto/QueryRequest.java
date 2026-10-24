package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /execute/query} 요청. Python 실행기 {@code QueryExecuteRequest} 와 같은 이름을
 * 쓴다(max_rows·read_only 는 snake_case, tenantId·readSlot 은 camelCase).
 *
 * @param tenantId 테넌트 id
 * @param query 조회(SELECT·WITH) 한 문장
 * @param maxRows 돌려줄 최대 행 수(1..10000) — 슬롯 스트리밍에서는 쓰지 않는다(행 상한 없음)
 * @param readOnly 읽기 전용 조회 요청인지 — true 가 아니면(false·누락) 거부한다
 * @param readSlot 읽기 슬롯 번호(1~10, WD-78) — {@code stream:true} 와 함께일 때만 받는다(슬롯 계정 스트리밍 조회)
 * @param stream NDJSON 스트리밍 응답 요청(WD-78) — {@code readSlot} 과 함께일 때만 받는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRequest(
    Long tenantId,
    String query,
    @JsonProperty("max_rows") Integer maxRows,
    @JsonProperty("read_only") Boolean readOnly,
    Integer readSlot,
    Boolean stream) {}

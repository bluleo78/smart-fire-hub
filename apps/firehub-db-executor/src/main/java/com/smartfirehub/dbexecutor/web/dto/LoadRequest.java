package com.smartfirehub.dbexecutor.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /execute/load} 요청. 필드 이름은 Python 실행기와 같다(tenantId 는 camelCase, 나머지는 snake_case).
 *
 * @param tenantId 테넌트 id — 저장소 기술자 조회 키
 * @param table 적재 대상 — 그 테넌트 스키마의 스테이징 테이블 이름({@code STG_<24hex>})만 허용
 * @param rows 행 목록(컬럼 이름 → JSON 값)
 * @param columnTypeMap 컬럼 이름 → 앱 타입(INTEGER·DECIMAL·BOOLEAN·DATE·TIMESTAMP·TEXT…). 없으면 값을 그대로 바인딩한다
 * @param timeoutSeconds 요청별 시간 제한(초, 선택). 없으면 {@code app.load.timeout-seconds}, 있으면 그 상한으로 자른다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoadRequest(
    Long tenantId,
    String table,
    List<Map<String, Object>> rows,
    @JsonProperty("column_type_map") Map<String, String> columnTypeMap,
    @JsonProperty("timeout_seconds") Integer timeoutSeconds) {}

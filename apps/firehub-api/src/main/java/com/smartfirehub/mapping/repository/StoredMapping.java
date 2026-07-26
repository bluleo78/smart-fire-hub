package com.smartfirehub.mapping.repository;

// MappingRepository 반환 형태 — spec은 raw JSON 문자열(서비스가 MappingSpec으로 역직렬화).
public record StoredMapping(long ontologyId, String specJson, String status) {}

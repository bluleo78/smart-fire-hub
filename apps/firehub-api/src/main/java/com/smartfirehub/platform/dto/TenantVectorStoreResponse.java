package com.smartfirehub.platform.dto;

/**
 * 테넌트 벡터 저장소 정보(WD-742) — 비밀(API 키)은 싣지 않는다.
 *
 * @param dedicated 전용 Qdrant 를 쓰는가(거짓이면 공유 Qdrant, 아래 값은 null)
 */
public record TenantVectorStoreResponse(
    boolean dedicated, String host, Integer grpcPort, Boolean tls) {}

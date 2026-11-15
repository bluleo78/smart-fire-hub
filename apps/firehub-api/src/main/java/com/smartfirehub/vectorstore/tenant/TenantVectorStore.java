package com.smartfirehub.vectorstore.tenant;

/**
 * 테넌트 전용 Qdrant 접속 정보(WD-742). {@code apiKeyEnc} 는 {@code EncryptionService} 암호문이다 — 이 값 그대로 응답에 싣지
 * 않는다.
 *
 * @param host gRPC 호스트
 * @param grpcPort gRPC 포트(Qdrant 기본 6334)
 * @param tls TLS 사용 여부
 * @param apiKeyEnc 그 인스턴스 API 키의 암호문 — 토큰 서명·컬렉션 관리에 쓴다
 */
public record TenantVectorStore(
    long tenantId, String host, int grpcPort, boolean tls, String apiKeyEnc) {}

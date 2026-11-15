package com.smartfirehub.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 테넌트 전용 벡터 저장소(Qdrant) 입력(WD-742) — 테넌트 생성 요청과 연결 확인 요청이 같이 쓴다. 행 검색 색인을 공유 Qdrant 가 아니라 이 인스턴스에
 * 둔다. 비어 있으면(요청에 없음) 공유 Qdrant 다.
 *
 * <p>API 키는 {@link #toString()} 이 가린다(로그·예외 메시지로 새지 않게).
 *
 * @param host gRPC 호스트(데이터 저장소 host 와 같은 문자 규칙)
 * @param grpcPort gRPC 포트(Qdrant 기본 6334 — REST 6333 이 아니다)
 * @param tls TLS 사용 여부
 * @param apiKey 그 인스턴스의 API 키 — 저장할 때 암호화한다
 */
public record VectorStoreSetupRequest(
    @NotNull @Pattern(regexp = DataStoreSetupRequest.HOST_REGEX, message = "host 형식이 올바르지 않습니다")
        String host,
    @NotNull @Min(1) @Max(65535) Integer grpcPort,
    boolean tls,
    @NotBlank @Size(max = 512) String apiKey) {

  /** 레코드 기본 toString 은 모든 필드를 찍으므로 재정의해 API 키를 가린다. */
  @Override
  public String toString() {
    return "VectorStoreSetupRequest[host="
        + host
        + ", grpcPort="
        + grpcPort
        + ", tls="
        + tls
        + ", apiKey=***]";
  }
}

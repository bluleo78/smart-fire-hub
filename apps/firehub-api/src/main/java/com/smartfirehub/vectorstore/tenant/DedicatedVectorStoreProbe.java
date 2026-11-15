package com.smartfirehub.vectorstore.tenant;

/** 테넌트 전용 벡터 저장소 연결 확인(WD-742). 벡터 저장소 종류가 이를 지원할 때만 빈이 있다(지금은 Qdrant) — 없으면 전용 인스턴스를 설정할 수 없다. */
public interface DedicatedVectorStoreProbe {

  /** 접속·API 키·테넌트 격리 토큰이 맞는지 본다. 실패는 {@code ok=false} 와 사유. */
  VectorStoreConnectionTestResult probe(String host, int grpcPort, boolean tls, String apiKey);
}

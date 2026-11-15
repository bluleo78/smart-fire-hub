package com.smartfirehub.vectorstore.tenant;

/**
 * 테넌트 전용 벡터 저장소(Qdrant) 연결 확인 결과(WD-742). 실패도 예외가 아니라 {@code ok=false} 로 돌려준다(데이터 저장소 연결 확인과 같은
 * 모양).
 *
 * @param ok 접속·API 키·컬렉션 토큰(JWT RBAC)이 모두 맞을 때 true
 * @param serverVersion Qdrant 버전(접속 실패면 null)
 * @param message 사용자에게 보여 줄 사유
 */
public record VectorStoreConnectionTestResult(boolean ok, String serverVersion, String message) {}

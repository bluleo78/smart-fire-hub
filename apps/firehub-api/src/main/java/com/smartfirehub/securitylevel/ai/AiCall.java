package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.securitylevel.access.ProviderHosting;

/**
 * AI 경로 1건의 판정 입력(스펙 §4.3).
 *
 * @param hosting 데이터가 갈 공급자의 위치(API 가 테넌트 AI 설정에서 계산 — agent 가 보낸 값은 쓰지 않는다)
 * @param share 결과가 공유 저장소·발송(GraphRAG·Proactive)으로 가는가 — true 면 SHARE 정책도 요구한다
 */
public record AiCall(ProviderHosting hosting, boolean share) {}

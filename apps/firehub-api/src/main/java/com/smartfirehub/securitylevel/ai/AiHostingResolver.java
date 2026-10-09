package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.service.AiCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI 공급자 호스팅 위치 계산(스펙 §4.3 "호스팅 위치는 API 가 테넌트 AI 설정에서 계산 — agent 가 보낸 값 불신"). 현재 테넌트(TenantContext)
 * 기준. 채팅 = 채팅 MCP·GraphRAG·Proactive, 분류 = AI_CLASSIFY, 임베딩 = 메타 임베딩·문서 청크·행 검색 색인.
 */
@Service
@RequiredArgsConstructor
public class AiHostingResolver {

  private final AiCredentialService aiCredentialService;
  private final EmbeddingConfigService embeddingConfigService;

  /** 채팅 공급자 호스팅(채팅 MCP·GraphRAG·Proactive 판정 입력). */
  public ProviderHosting chat() {
    return aiCredentialService.hosting(AiCredentialSlot.CHAT);
  }

  /** AI_CLASSIFY 가 실제로 쓸 공급자 호스팅 — 분류 전용 슬롯이 없으면 채팅을 따른다. */
  public ProviderHosting classify() {
    return aiCredentialService.classifyHosting();
  }

  /** 임베딩 공급자 호스팅(메타 임베딩·문서 청크·행 검색 색인 판정 입력). */
  public ProviderHosting embedding() {
    return embeddingConfigService.hosting();
  }

  /**
   * 공유 목적(GraphRAG·Proactive) AI 경로의 호스팅. graphrag_ingest 는 추출한 엔티티 이름을 api 임베딩 엔드포인트로 보내 임베딩
   * 공급자에게도 데이터가 간다 — 채팅만 자체 호스팅이고 임베딩이 외부면 민감 문서 유래 이름이 외부로 나간다. 그래서 둘 다 자체 호스팅일 때만 자체 호스팅으로 본다.
   */
  public ProviderHosting forShare() {
    return chat() == ProviderHosting.SELF_HOSTED && embedding() == ProviderHosting.SELF_HOSTED
        ? ProviderHosting.SELF_HOSTED
        : ProviderHosting.EXTERNAL;
  }
}

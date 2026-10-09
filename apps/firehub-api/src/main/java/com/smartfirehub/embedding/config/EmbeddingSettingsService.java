package com.smartfirehub.embedding.config;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigView;
import com.smartfirehub.embedding.config.dto.EmbeddingImpact;
import com.smartfirehub.embedding.config.dto.EmbeddingProbeResponse;
import com.smartfirehub.embedding.reembed.EmbeddingBacklogService;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.HostingChangeAuditor;
import com.smartfirehub.securitylevel.ai.HostingDeclarationPolicy;
import com.smartfirehub.settings.service.AiCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 설정 화면의 세 동작(연결 테스트·영향도·저장)을 묶는다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> probe 는 외부 HTTP 라 DB 커넥션을 잡은 채 기다리면 안 된다. 저장은 저장소 자체 트랜잭션이 GUC 를 세운다. 서버
 * 저장은 클라이언트가 먼저 잰 차원을 믿지 않고 다시 probe 한다.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingSettingsService {

  private final EmbeddingConfigService configService;
  private final EmbeddingProviderFactory providerFactory;
  private final EmbeddingBacklogService backlogService;
  private final TenantReembedJob reembedJob;
  private final HostingDeclarationPolicy hostingPolicy;
  private final HostingChangeAuditor hostingChangeAuditor;

  public EmbeddingConfigView view() {
    return configService.view();
  }

  /** 저장 없이 probe 만. 실패 원인(인증/연결/모델 없음)은 400 으로 그대로 전달한다. */
  public EmbeddingProbeResponse test(EmbeddingConfigRequest req) {
    return new EmbeddingProbeResponse(measure(configService.prepare(req)).size());
  }

  /**
   * 호스팅 판정 → 검증 → probe 로 차원 측정 → 저장 → 호스팅이 바뀌었으면 감사. 자체 호스팅으로 올리는 선언은 security:settings 가 필요하다(스펙
   * §2.6) — 외부 호출(probe) 전에 판정해 거부 시 아무것도 쓰지 않는다.
   */
  public EmbeddingConfigView save(EmbeddingConfigRequest req, Long userId) {
    ProviderHosting before = configService.hosting();
    // 생략 = 기존 선언 유지. 모르는 값은 400.
    ProviderHosting after = req.hosting() == null ? before : parseHosting(req.hosting());
    hostingPolicy.requireChangeAllowed(userId, before, after);
    EmbeddingConfig draft = configService.prepare(req);
    EmbeddingDimension dimension = measure(draft);
    configService.store(draft, dimension, after, userId);
    // 저장 성공 뒤에만 감사(R3) — 값이 같으면 기록하지 않는다.
    hostingChangeAuditor.recordIfChanged(
        userId, HostingChangeAuditor.Slot.EMBEDDING, before, configService.hosting());
    // 판정식 한 규칙(스펙 §3.3-4): 이전 문서와 비교하지 않고, 지금 공간에 없는 벡터가 있으면 투입한다.
    EmbeddingSpace space = new EmbeddingSpace(dimension, draft.model());
    if (backlogService.hasWork(space)) {
      reembedJob.enqueue(TenantContext.require());
    }
    return configService.view();
  }

  /** "EXTERNAL"/"SELF_HOSTED" 외 값은 400(채팅 자격증명과 같은 문구). */
  private static ProviderHosting parseHosting(String raw) {
    try {
      return ProviderHosting.valueOf(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(AiCredentialService.MSG_HOSTING_INVALID);
    }
  }

  /** 그 설정(model, dimension)으로 저장했을 때의 재임베딩 대상 수 — 판정식과 같은 쿼리(확인 창용). */
  public EmbeddingImpact impact(String model, int dimension) {
    EmbeddingSpace space =
        new EmbeddingSpace(EmbeddingDimension.of(dimension), model == null ? "" : model.trim());
    return backlogService.impact(space);
  }

  /** probe 1건 → 지원 차원. 공급자 실패는 원인 문구를 살린 400 으로 바꾼다(전역 핸들러는 EmbeddingException 을 502 로 본다). */
  EmbeddingDimension measure(EmbeddingConfig draft) {
    int measured;
    try {
      measured = providerFactory.probeDimension(draft);
    } catch (EmbeddingException e) {
      throw new IllegalArgumentException("임베딩 연결 테스트 실패: " + e.getMessage(), e);
    }
    return EmbeddingDimension.of(measured);
  }
}

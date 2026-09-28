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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 설정 화면의 세 동작(연결 테스트·영향도·저장)을 묶는다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> probe 는 외부 HTTP 라 DB 커넥션을 잡은 채 기다리면 안 된다. 저장은
 * 저장소 자체 트랜잭션이 GUC 를 세운다. 서버 저장은 클라이언트가 먼저 잰 차원을 믿지 않고 다시 probe 한다.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingSettingsService {

  private final EmbeddingConfigService configService;
  private final EmbeddingProviderFactory providerFactory;
  private final EmbeddingBacklogService backlogService;
  private final TenantReembedJob reembedJob;

  public EmbeddingConfigView view() {
    return configService.view();
  }

  /** 저장 없이 probe 만. 실패 원인(인증/연결/모델 없음)은 400 으로 그대로 전달한다. */
  public EmbeddingProbeResponse test(EmbeddingConfigRequest req) {
    return new EmbeddingProbeResponse(measure(configService.prepare(req)).size());
  }

  /** 검증 → probe 로 차원 측정 → 저장. */
  public EmbeddingConfigView save(EmbeddingConfigRequest req, Long userId) {
    EmbeddingConfig draft = configService.prepare(req);
    EmbeddingDimension dimension = measure(draft);
    configService.store(draft, dimension, userId);
    // 판정식 한 규칙(스펙 §3.3-4): 이전 문서와 비교하지 않고, 지금 공간에 없는 벡터가 있으면 투입한다.
    EmbeddingSpace space = new EmbeddingSpace(dimension, draft.model());
    if (backlogService.hasWork(space)) {
      reembedJob.enqueue(TenantContext.require());
    }
    return configService.view();
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

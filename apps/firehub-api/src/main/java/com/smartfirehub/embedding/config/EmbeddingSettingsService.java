package com.smartfirehub.embedding.config;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigView;
import com.smartfirehub.embedding.config.dto.EmbeddingProbeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 설정 화면의 세 동작(연결 테스트·저장, Task 4 에서 영향도)을 묶는다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> probe 는 외부 HTTP 라 DB 커넥션을 잡은 채 기다리면 안 된다. 저장은
 * 저장소 자체 트랜잭션이 GUC 를 세운다. 서버 저장은 클라이언트가 먼저 잰 차원을 믿지 않고 다시 probe 한다.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingSettingsService {

  private final EmbeddingConfigService configService;
  private final EmbeddingProviderFactory providerFactory;

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
    return configService.view();
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

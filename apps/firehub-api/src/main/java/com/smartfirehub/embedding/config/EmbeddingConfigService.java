package com.smartfirehub.embedding.config;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigView;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 테넌트 임베딩 설정({@code tenant_settings.embedding.config})의 <b>유일한 소유자</b>(#713).
 *
 * <p><b>테넌트 전용.</b> 행이 없거나 테넌트 컨텍스트가 없으면 "미설정"이다 — {@code system_settings} 는 읽지도 쓰지도 않는다(#706 과 같은
 * 방향). 범용 설정 경로는 이 키를 {@code EXTERNAL_OWNER} 로 막는다.
 *
 * <p><b>복호화는 여기서만.</b> 실제 호출용 {@link #resolve} 는 손상을 감추지 않고(fail-closed) 던지고, 화면·병합용 {@link
 * #resolveLenient} 는 손상을 "미설정"으로 보여 관리자가 다시 저장해 복구할 수 있게 한다 ({@code AiCredentialService} 의
 * decryptOrEmpty / Lenient 구분과 같은 이유).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingConfigService {

  public static final String KEY = "embedding.config";

  static final String MSG_MODEL_REQUIRED = "임베딩 모델은 비어있을 수 없습니다";
  static final String MSG_MODEL_TOO_LONG = "임베딩 모델 이름은 100자 이하여야 합니다";
  static final String MSG_API_KEY_REQUIRED = "OpenAI 임베딩 provider 에는 API 키가 필요합니다";
  static final String MSG_BASE_URL_CHANGED = "Base URL 을 바꾸면 API 키를 다시 입력해야 합니다";
  static final String MSG_CORRUPT = "임베딩 설정이 손상됐습니다 (설정 > 임베딩에서 다시 저장하세요)";

  private final TenantSettingsRepository tenantSettingsRepository;
  private final EncryptionService encryptionService;
  private final EmbeddingTargetGuard targetGuard;

  /** 실제 호출용 해석(키 복호화). 미설정이면 empty, 손상이면 EmbeddingException(fail-closed). */
  public Optional<EmbeddingConfig> resolve() {
    Optional<String> raw = readRaw();
    if (raw.isEmpty()) return Optional.empty();
    try {
      return Optional.of(toConfig(EmbeddingConfigDocument.parse(raw.get())));
    } catch (RuntimeException e) {
      throw new EmbeddingException(MSG_CORRUPT, e);
    }
  }

  /**
   * 현재 공간(차원, 모델)만 — 복호화하지 않는다. 재임베딩 잡이 배치마다 "설정이 바뀌었나"를 보려고 부르므로 가볍게 둔다. 손상·미지원 차원은 empty(로그만).
   */
  public Optional<EmbeddingSpace> currentSpace() {
    return readRaw()
        .flatMap(
            raw -> {
              try {
                EmbeddingConfigDocument.Parsed p = EmbeddingConfigDocument.parse(raw);
                return Optional.of(
                    new EmbeddingSpace(EmbeddingDimension.of(p.dimension()), p.model()));
              } catch (RuntimeException e) {
                log.warn("{} 문서를 공간으로 해석할 수 없다 — 미설정으로 본다: {}", KEY, e.toString());
                return Optional.empty();
              }
            });
  }

  /** 임베딩 공급자 호스팅 위치. 미설정·손상·값 없음은 외부(기본 외부 — 스펙 §3). 복호화하지 않는다. */
  public ProviderHosting hosting() {
    return readRaw()
        .flatMap(
            raw -> {
              try {
                return Optional.of(EmbeddingConfigDocument.parse(raw).hosting());
              } catch (RuntimeException e) {
                log.warn("{} 문서를 해석할 수 없다 — 호스팅은 외부로 본다: {}", KEY, e.toString());
                return Optional.empty();
              }
            })
        .map(h -> "SELF_HOSTED".equals(h) ? ProviderHosting.SELF_HOSTED : ProviderHosting.EXTERNAL)
        .orElse(ProviderHosting.EXTERNAL);
  }

  /**
   * 요청의 전송 대상(provider, 정규화한 Base URL)이 저장된 값과 다른가. 복호화하지 않는다 — 손상된 암호문이 판정을 막지 않게 한다. 미설정·손상·모르는
   * provider 는 "바뀜"(보수적: 기존 자체 호스팅 선언을 이어받지 않는다). 모델은 목적지를 바꾸지 않으므로 보지 않는다.
   */
  public boolean targetChanged(EmbeddingConfigRequest req) {
    EmbeddingProviderType provider;
    try {
      provider = EmbeddingProviderType.parse(req.provider());
    } catch (RuntimeException e) {
      return true;
    }
    String baseUrl = UrlUtils.normalizeBaseUrl(req.baseUrl() == null ? "" : req.baseUrl().trim());
    return readRaw()
        .flatMap(
            raw -> {
              try {
                return Optional.of(EmbeddingConfigDocument.parse(raw));
              } catch (RuntimeException e) {
                return Optional.empty();
              }
            })
        .map(
            p ->
                p.provider() != provider
                    || !UrlUtils.normalizeBaseUrl(p.baseUrl().trim()).equals(baseUrl))
        .orElse(true);
  }

  /** 화면용 읽기. 키는 마스킹만 내보낸다(평문·암호문 금지). */
  public EmbeddingConfigView view() {
    return resolveLenient()
        .map(
            c ->
                new EmbeddingConfigView(
                    true,
                    c.provider().name(),
                    c.model(),
                    c.baseUrl(),
                    c.dimension() > 0 ? c.dimension() : null,
                    c.apiKey().isBlank() ? "" : encryptionService.maskValue(c.apiKey()),
                    hosting().name()))
        .orElseGet(EmbeddingConfigView::notConfigured);
  }

  /**
   * 요청을 검증하고 저장된 키와 병합한 설정 초안(dimension=0)을 만든다 — 아직 쓰지 않는다. 순서: provider → model → Base URL
   * 가드(형식·SSRF) → 키 병합.
   */
  public EmbeddingConfig prepare(EmbeddingConfigRequest req) {
    return prepareInternal(req, true);
  }

  /** 테스트 전용 — SSRF 가드(DNS 해석)만 건너뛰고 {@link #prepare} 와 같은 병합 규칙을 탄다. */
  EmbeddingConfig prepareForTest(EmbeddingConfigRequest req) {
    return prepareInternal(req, false);
  }

  /** {@link #prepare} 본체. {@code checkTarget=false} 는 테스트 전용 경로뿐이다(가드 외 규칙은 동일). */
  private EmbeddingConfig prepareInternal(EmbeddingConfigRequest req, boolean checkTarget) {
    EmbeddingProviderType provider = EmbeddingProviderType.parse(req.provider());
    String model = requireModel(req.model());
    String baseUrl = req.baseUrl() == null ? "" : req.baseUrl().trim();
    if (checkTarget) targetGuard.check(provider, baseUrl);
    return merge(provider, model, UrlUtils.normalizeBaseUrl(baseUrl), req.apiKey());
  }

  /**
   * 측정 차원을 넣어 문서를 저장한다(현재 테넌트 행 하나, 통째 교체). {@code TenantSettingsRepository} 의 클래스 레벨 트랜잭션이 RLS GUC
   * 를 세운다. {@code hosting} 은 공급자 호스팅 위치 선언으로 문서 최상위에 같이 쓴다(권한 판정은 호출부 몫).
   */
  public void store(
      EmbeddingConfig config, EmbeddingDimension dimension, ProviderHosting hosting, Long userId) {
    TenantContext.require("임베딩 설정 저장");
    String cipher =
        config.apiKey() == null || config.apiKey().isBlank()
            ? ""
            : encryptionService.encrypt(config.apiKey());
    tenantSettingsRepository.upsert(
        KEY,
        EmbeddingConfigDocument.toJson(
            config.provider(),
            config.model(),
            config.baseUrl(),
            dimension.size(),
            cipher,
            hosting.name()),
        userId);
  }

  // ---- 내부 ----

  private static String requireModel(String raw) {
    String model = raw == null ? "" : raw.trim();
    if (model.isEmpty()) throw new IllegalArgumentException(MSG_MODEL_REQUIRED);
    // 벡터 테이블 embedding_model 은 VARCHAR(100) — 넘으면 저장 뒤 쓰기 경로가 22001 로 깨진다.
    if (model.length() > 100) throw new IllegalArgumentException(MSG_MODEL_TOO_LONG);
    return model;
  }

  /**
   * 키 병합. Ollama 는 키를 쓰지 않으므로 항상 빈 값(저장된 OpenAI 키를 Ollama 주소로 보내지 않는다). OpenAI 는 새 키가 있으면 그 값, 없으면
   * <b>provider·Base URL 이 저장된 값과 같을 때만</b> 저장된 키를 쓴다 — 다르면 관리자(또는 CSRF)가 Base URL 만 바꿔 저장된 키를 임의
   * 호스트로 보낼 수 있다(OpencodeProbeService 의 MSG_BASE_URL_MISMATCH 와 같은 규칙).
   */
  private EmbeddingConfig merge(
      EmbeddingProviderType provider, String model, String baseUrl, String submittedKey) {
    if (provider == EmbeddingProviderType.OLLAMA) {
      return new EmbeddingConfig(provider, model, baseUrl, "", 0);
    }
    if (submittedKey != null && !submittedKey.isBlank()) {
      return new EmbeddingConfig(provider, model, baseUrl, submittedKey.trim(), 0);
    }
    EmbeddingConfig stored =
        resolveLenient()
            .filter(c -> c.provider() == EmbeddingProviderType.OPENAI && !c.apiKey().isBlank())
            .orElseThrow(() -> new IllegalArgumentException(MSG_API_KEY_REQUIRED));
    if (!UrlUtils.normalizeBaseUrl(stored.baseUrl()).equals(baseUrl)) {
      throw new IllegalArgumentException(MSG_BASE_URL_CHANGED);
    }
    return new EmbeddingConfig(provider, model, baseUrl, stored.apiKey(), 0);
  }

  /** 관용 해석 — 파싱·복호화 실패를 "미설정"으로(로그만). 화면과 병합 경로 전용. */
  private Optional<EmbeddingConfig> resolveLenient() {
    return readRaw()
        .flatMap(
            raw -> {
              try {
                return Optional.of(toConfig(EmbeddingConfigDocument.parse(raw)));
              } catch (RuntimeException e) {
                log.warn("{} 문서가 손상돼 미설정으로 취급한다: {}", KEY, e.toString());
                return Optional.empty();
              }
            });
  }

  private EmbeddingConfig toConfig(EmbeddingConfigDocument.Parsed p) {
    String apiKey = p.apiKeyCipher().isBlank() ? "" : encryptionService.decrypt(p.apiKeyCipher());
    return new EmbeddingConfig(p.provider(), p.model(), p.baseUrl(), apiKey, p.dimension());
  }

  /** 컨텍스트 없으면 조회하지 않는다(어느 테넌트 값인지 모른다 — 읽을 플랫폼 값도 없다). */
  private Optional<String> readRaw() {
    if (TenantContext.get() == null) return Optional.empty();
    return tenantSettingsRepository.findValue(KEY);
  }
}

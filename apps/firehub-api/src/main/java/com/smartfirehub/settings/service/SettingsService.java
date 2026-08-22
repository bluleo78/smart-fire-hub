package com.smartfirehub.settings.service;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.global.security.PlatformAuthentication;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.repository.SettingsRepository;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

  /**
   * <b>플랫폼 쓰기 경로({@link #updatePlatformSettings})의 화이트리스트다(P7-b 이전에는
   * {@link #updateSettings} 자신의 화이트리스트였다).</b> 테넌트 오버라이드 허용 키
   * ({@link SettingsOverridePolicy#tenantOverridableKeys}) 는 이 9키를 포함한 세 서브
   * 화이트리스트({@link #ALLOWED_AI_KEYS}/{@link #ALLOWED_SMTP_KEYS}/{@link #ALLOWED_EMBEDDING_KEYS})
   * 의 <b>합집합의 부분집합</b>이다 — {@code ai.api_key}/{@code ai.agent_type}/
   * {@code ai.cli_oauth_token} 은 여기 있지만 테넌트 화이트리스트에는 없다(과금 주체·실행 형태라
   * 플랫폼이 갖는다).
   */
  // 패키지 가시성: SettingsKeyWhitelistInvariantTest 가 "테넌트 허용 키 ⊆ 플랫폼 쓰기 가능 키
  // 합집합" 불변식을 실행 가능한 단언으로 고정한다(javadoc 문장만으로는 깨져도 아무도 모른다).
  static final Set<String> ALLOWED_AI_KEYS =
      Set.of(
          "ai.model",
          "ai.max_turns",
          "ai.system_prompt",
          "ai.temperature",
          "ai.max_tokens",
          "ai.session_max_tokens",
          "ai.api_key",
          "ai.agent_type",
          "ai.cli_oauth_token");

  /**
   * SMTP 6키. P7-c1(2026-08-22)부터 전부 테넌트 오버라이드 허용이다 — {@link
   * SettingsOverridePolicy#tenantOverridableKeys} 참조. 패키지 가시성 이유는 {@link
   * #ALLOWED_AI_KEYS} 와 같다.
   */
  static final Set<String> ALLOWED_SMTP_KEYS =
      Set.of(
          "smtp.host",
          "smtp.port",
          "smtp.username",
          "smtp.password",
          "smtp.starttls",
          "smtp.from_address");

  // 임베딩 provider 설정 키 (V63 시드). embedding.api_key 는 ai.api_key 와 동일하게 암호화/마스킹 처리한다.
  // 4키 전부 플랫폼 잠금이라 테넌트 화이트리스트에 대응하는 부분집합이 없다 — 근거는 ALLOWED_AI_KEYS 와 같다.
  // 패키지 가시성 이유는 ALLOWED_AI_KEYS 와 같다(SettingsKeyWhitelistInvariantTest 가 읽는다).
  static final Set<String> ALLOWED_EMBEDDING_KEYS =
      Set.of("embedding.provider", "embedding.model", "embedding.base_url", "embedding.api_key");

  /**
   * 암호화 저장되는 비밀 키의 집합. 마스킹 판정의 <b>단일 출처</b>다.
   *
   * <p>{@code smtp.password} 가 빠져 있었다. 플랫폼 SMTP 쓰기는 이 키를 암호화해
   * 저장하는데 {@link #maskSecret} 이 그것을 모르면 {@code getAll}/{@code getByPrefix("smtp")} 가
   * <b>암호문을 그대로</b> 내보낸다(당시 SMTP 전용 읽기 메서드만 별도로 마스킹하고 있었다 — 즉 이
   * 목록은 이미 한 번 어긋난 상태였다). 새 비밀 키를 추가할 때는 <b>여기만</b> 고친다.
   */
  private static final Set<String> SECRET_KEYS =
      Set.of("ai.api_key", "ai.cli_oauth_token", "embedding.api_key", "smtp.password");

  private final SettingsRepository settingsRepository;
  private final EncryptionService encryptionService;
  private final TenantSettingsRepository tenantSettingsRepository;

  @Transactional(readOnly = true)
  public List<SettingResponse> getByPrefix(String prefix) {
    return settingsRepository.findByPrefix(prefix).stream()
        .map(this::maskSecret)
        .collect(Collectors.toList());
  }

  /**
   * 전체 설정(18키). 운영자 평면이 플랫폼 기본값을 한 화면에 보여 주기 위해 쓴다.
   *
   * <p>마스킹은 {@link #getByPrefix} 와 <b>같은 함수</b>를 지난다 — 복사해 두면 한쪽에 비밀 키가
   * 추가될 때 다른 쪽이 평문을 노출한다.
   */
  @Transactional(readOnly = true)
  public List<SettingResponse> getAll() {
    return settingsRepository.findAll().stream().map(this::maskSecret).collect(Collectors.toList());
  }

  /** 비밀값은 복호화 후 마스킹해서 내보낸다. 평문도, 암호문도 응답에 실리지 않는다. */
  private SettingResponse maskSecret(SettingResponse setting) {
    String masked = maskIfSecret(setting.key(), setting.value());
    if (masked == setting.value()) return setting;
    return new SettingResponse(setting.key(), masked, setting.description(), setting.updatedAt());
  }

  /**
   * 마스킹 판정의 <b>값 단위</b> 형태. {@link #maskSecret}(플랫폼 행)과
   * {@link #getResolvedByPrefix}(테넌트 오버라이드 값)가 공유한다 — 오버라이드 쪽에 판정을 복사하면
   * {@link #SECRET_KEYS} 에 키가 추가될 때 한쪽만 반영되어 다시 암호문이 새어 나간다.
   *
   * <p>비밀 키가 아니면 <b>받은 참조를 그대로</b> 돌려준다. {@link #maskSecret} 이 그 동일성으로
   * "마스킹이 일어났는가"를 판정해 불필요한 DTO 재생성을 피한다.
   */
  private String maskIfSecret(String key, String value) {
    if (!SECRET_KEYS.contains(key)) return value;
    return value == null || value.isBlank()
        ? ""
        : encryptionService.maskValue(encryptionService.decrypt(value));
  }

  /**
   * 설정 값 해석. {@code tenant_settings} 에 값이 있으면 그 값, 없으면 {@code system_settings}.
   *
   * <p><b>컨텍스트가 없으면 예외를 던지지 않는다.</b> {@code TenantContext.require()} 를 쓰면
   * JobRunr {@code @Job}·{@code @Async}·{@code @Scheduled} 배경 경로가 전멸한다(설계서 §4.5,
   * P3-a·P2-g 에서 두 번 겪음). 컨텍스트 없음은 "플랫폼 기본값"이라는 정상 분기다.
   *
   * <p><b>화이트리스트를 읽기 쪽에서도 확인한다.</b> 쓰기에서 막았으니 읽기는 안 봐도 된다는
   * 것은 "지금 DB 에 잠긴 키의 오버라이드 행이 없다"는 가정에 기대는 것인데, 화이트리스트가
   * 좁아지면(키를 플랫폼으로 회수하면) 그 가정이 깨진다. 읽기에서 다시 보면 회수가 즉시
   * 효력을 갖는다. 이 판정은 {@link #getValue} 와 {@link #resolveOverridesByPrefix} 각각에 있고 {@link #getAsMap}·
   * {@link #getResolvedByPrefix} 가 같은 헬퍼를 공유한다 — 세 곳에 복사하면 화이트리스트가
   * 좁아질 때 일부만 반영되는 드리프트가 생긴다.
   */
  @Transactional(readOnly = true)
  public Optional<String> getValue(String key) {
    // 컨텍스트가 없으면(배경 잡 경로) DB 조회조차 하지 않고 플랫폼 값으로 간다 —
    // "컨텍스트 없음 = 오버라이드 없음 = 항상 플랫폼 값" 계약이다. 화이트리스트를 읽기에서 다시
    // 보는 이유는 위 javadoc 참고(키를 플랫폼으로 회수하면 즉시 효력을 갖는다).
    if (TenantContext.get() != null && SettingsOverridePolicy.isTenantOverridable(key)) {
      Optional<String> override = tenantSettingsRepository.findValue(key);
      if (override.isPresent()) return override;
    }
    return settingsRepository.getValue(key);
  }

  /**
   * <b>교집합이 아니라 합집합.</b> {@code system_settings} 에 행이 없는 오버라이드 허용 키(예:
   * {@code ai.session_max_tokens} — 어떤 마이그레이션도 이 키를 시드하지 않았고
   * {@code SettingsRepository.updateSettings} 가 UPDATE-only 라 오늘 이 키에 쓰면 0행이 갱신된다)는
   * 플랫폼 맵을 먼저 만들고 그 키 집합만 오버라이드 조회에 넘기면 <b>절대 드러나지 않는다</b>.
   * 오버라이드 자체를 프리픽스로 통째로 가져와 덮어써야, 플랫폼 행이 없는 키의 오버라이드도 결과에
   * 나타난다. AI 채팅·프로액티브 잡이 실제로 읽는 경로라 이 구멍은 "오버라이드를 저장했는데 실제
   * 호출은 여전히 하드코딩 폴백을 쓴다"는 형태로 조용히 발현한다.
   */
  @Transactional(readOnly = true)
  public Map<String, String> getAsMap(String prefix) {
    // system_settings.value 컬럼은 nullable이므로 null value가 있으면 Collectors.toMap이 NPE를 발생시킨다.
    // null value는 빈 문자열로 대체하고, 중복 키 발생 시 나중 값(b)을 사용하는 merge function을 지정한다.
    Map<String, String> platform =
        settingsRepository.findByPrefix(prefix).stream()
            .collect(
                Collectors.toMap(
                    SettingResponse::key, s -> s.value() != null ? s.value() : "", (a, b) -> b));

    Map<String, String> overrides = resolveOverridesByPrefix(prefix);
    Map<String, String> resolved = new HashMap<>(platform);
    resolved.putAll(overrides);
    return resolved;
  }

  /**
   * 프리픽스에 속한 설정을 {@code overridden}/{@code tenantEditable} 플래그와 함께 해석한다. web
   * 목록 화면 전용 — 실제 런타임 호출부는 {@link #getValue}/{@link #getAsMap} 을 쓴다.
   *
   * <p>{@link #getAsMap} 과 같은 이유로 합집합이다 — 플랫폼 행이 없는 오버라이드 키도 목록에
   * 나타나야 한다. 그런 키는 {@code description}/{@code updatedAt} 을 줄 시스템 설정 행이 없으므로
   * {@code null} 이다({@link TenantSettingsRepository#findByPrefix} 가 값만 주고 갱신 시각은 주지
   * 않아 오버라이드 쪽에서도 채울 수 없다).
   *
   * <p><b>플랫폼 행은 {@link #maskSecret} 을 지난다.</b> 이 경로만 빠뜨리면 {@code prefix=ai} 조회가
   * {@code ai.api_key} 의 <b>AES 암호문을 그대로</b> 내보낸다 — {@link #SECRET_KEYS} javadoc 이
   * 기록하듯 이 프로젝트는 정확히 그 사고를 이미 한 번 냈다(SMTP 전용 읽기 메서드만 마스킹하고
   * {@code getAll} 은 빠뜨렸던 건).
   *
   * <p><b>오버라이드 값도 {@link #maskIfSecret} 을 지난다.</b> P7-c1 이전 이 자리에는 "오버라이드
   * 값은 마스킹하지 않아도 된다 — 비밀 키 4개는 전부 플랫폼 잠금이라 오버라이드 행으로 존재할 수
   * 없다"고 적혀 있었고, 그 전제는 당시 참이었다. <b>P7-c1 Task 1 이 {@code smtp.*} 6키를 테넌트
   * 오버라이드로 열면서 그 전제가 죽었다</b> — {@code smtp.password} 는 {@link #SECRET_KEYS} 의
   * 원소이므로 이제 오버라이드 행으로 실재하고, 마스킹을 빠뜨리면 <b>테넌트 자기 비밀번호의 AES
   * 암호문이 그대로 응답에 실린다</b>. 그 문단은 "이 누락이 어떤 테스트에도 걸리지 않았던 이유"까지
   * 스스로 적어 두고 있었다.
   */
  @Transactional(readOnly = true)
  public List<ResolvedSettingResponse> getResolvedByPrefix(String prefix) {
    List<SettingResponse> platform =
        settingsRepository.findByPrefix(prefix).stream().map(this::maskSecret).toList();
    Map<String, SettingResponse> platformByKey =
        platform.stream().collect(Collectors.toMap(SettingResponse::key, s -> s));
    Map<String, String> overrides = resolveOverridesByPrefix(prefix);

    // 순서는 플랫폼 키 먼저(기존 화면 순서 유지) + 플랫폼에 없는 오버라이드 전용 키를 뒤에 덧붙인다.
    java.util.LinkedHashSet<String> allKeys = new java.util.LinkedHashSet<>(platformByKey.keySet());
    allKeys.addAll(overrides.keySet());

    return allKeys.stream()
        .map(
            key -> {
              SettingResponse platformRow = platformByKey.get(key);
              boolean overridden = overrides.containsKey(key);
              // 플랫폼 행은 위에서 이미 maskSecret 을 지났고, 오버라이드 값은 여기서 지난다.
              String value =
                  overridden ? maskIfSecret(key, overrides.get(key)) : platformRow.value();
              return new ResolvedSettingResponse(
                  key,
                  value,
                  platformRow != null ? platformRow.description() : null,
                  platformRow != null ? platformRow.updatedAt() : null,
                  overridden,
                  SettingsOverridePolicy.isTenantOverridable(key));
            })
        .collect(Collectors.toList());
  }



  /**
   * 오버라이드 판정의 <b>프리픽스 형태</b>. {@link #getAsMap}·{@link #getResolvedByPrefix} 가
   * 공유한다. {@link TenantSettingsRepository#findByPrefix} 로 <b>한 번의 쿼리</b>에 후보를 전부
   * 가져온 뒤 화이트리스트로 걸러낸다 — 키마다 {@code findValue} 를 부르던 이전 구현은 프리픽스당
   * N+1 쿼리를 냈다.
   *
   * <p>컨텍스트가 없으면 즉시 빈 맵(쿼리 없음) — {@link #resolveOverrides} 와 같은 계약이다.
   */
  private Map<String, String> resolveOverridesByPrefix(String prefix) {
    if (TenantContext.get() == null) return Map.of();
    Map<String, String> candidates = tenantSettingsRepository.findByPrefix(prefix);
    candidates.keySet().removeIf(key -> !SettingsOverridePolicy.isTenantOverridable(key));
    return candidates;
  }

  /**
   * <b>테넌트 평면</b> 쓰기. {@link SettingsOverridePolicy#isTenantOverridable} 화이트리스트
   * (12키: {@code ai.*} 6 + {@code smtp.*} 6, P7-c1 재분류 이후)만 받아 {@code tenant_settings}
   * 에 저장한다 — {@code system_settings}(전역 18행)는 절대 건드리지 않는다. 이 구분이 이 밴드의
   * 존재 이유다(오늘의 결함: 한 테넌트의 저장이 전 테넌트에 적용됨).
   *
   * <p>거부 메시지에 키 이름을 넣는다 — web 이 어느 필드가 잠겼는지 사용자에게 보여줄 수 있어야
   * 하기 때문이다. 플랫폼 잠금 키(자격증명·{@code embedding.*}) 는 {@link #updatePlatformSettings}
   * 로만 바뀐다.
   *
   * <p>{@link #validateValues} 는 그대로 지난다 — 범위 검증(예: max_turns 1~50)은 값이
   * {@code tenant_settings} 로 가든 {@code system_settings} 로 가든 똑같이 필요하다.
   *
   * <p><b>SMTP 키는 {@link #normalizeSmtpPayload} 를, 모든 키는 {@link #encryptSecrets} 를 지난다.</b> P7-c1 이전 이 자리에는
   * "마스킹 필터·{@link #encryptIfSecret} 는 여기서 쓰지 않는다 — 허용 키 중 {@link #SECRET_KEYS}
   * 에 속하는 키가 하나도 없다"고 적혀 있었고, Task 1 이 {@code smtp.*} 를 열면서 그 근거가
   * 죽었다({@code smtp.password} 는 {@link #SECRET_KEYS} 의 원소다). 그 한 줄이 세 결함을 동시에
   * 깨웠다 — 평문 저장·마스크 센티널 덮어쓰기·포트 검증 부재.
   *
   * <p>{@code @Transactional} 이 필수다 — {@code tenant_settings} 는 RLS 테이블이라 GUC 가
   * 트랜잭션이 열릴 때만 주입된다({@code TenantAwareTransactionManager.doBegin}). 트랜잭션 없이
   * {@link TenantSettingsRepository#upsert} 를 부르면 GUC 없는 커넥션에서 INSERT 가 정책 위반으로
   * 거부된다.
   */
  @Transactional
  public void updateSettings(Map<String, String> settings, Long userId) {
    rejectNullValues(settings);
    for (String key : settings.keySet()) {
      if (!SettingsOverridePolicy.isTenantOverridable(key)) {
        throw new IllegalArgumentException("플랫폼 관리자만 변경할 수 있는 설정입니다: " + key);
      }
    }

    validateValues(settings);

    // SMTP 키만 정규화(포트 검증·센티널 제거)를 지난다 — 그 두 규칙은 SMTP 고유다.
    Map<String, String> merged = new HashMap<>(settings);
    merged.keySet().removeAll(ALLOWED_SMTP_KEYS);
    merged.putAll(
        normalizeSmtpPayload(
            settings.entrySet().stream()
                .filter(e -> ALLOWED_SMTP_KEYS.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));

    // 암호화는 **합친 맵 전체**가 지난다. SMTP 부분맵에만 걸면 "암호화 대상인가"의 답이
    // SECRET_KEYS 가 아니라 프리픽스에서 나오고, SMTP 아닌 비밀 키가 테넌트에 열리는 순간
    // 평문으로 저장된다(BYO 키 정책 → ai.api_key 화이트리스트 한 줄). 오늘 그 조합은 존재하지
    // 않지만, "오늘은 그런 키가 없다"가 바로 이 밴드가 방금 죽인 문장이다.
    Map<String, String> toWrite = encryptSecrets(merged);

    // 저장 대상은 **여기서만** 정한다 — 쓰기까지 공유 헬퍼에 넣으면 두 평면이 저장 대상에서
    // 갈라질 때 조용히 어긋난다(플랫폼은 system_settings, 테넌트는 tenant_settings).
    toWrite.forEach((key, value) -> tenantSettingsRepository.upsert(key, value, userId));
  }

  /**
   * <b>플랫폼 평면</b> 쓰기(운영자 전용, Task 6). AI·임베딩·SMTP 18키 전체를 대상으로 하고
   * {@code system_settings} 에 쓴다. 세 서브 화이트리스트({@link #ALLOWED_AI_KEYS} /
   * {@link #ALLOWED_EMBEDDING_KEYS} / {@link #ALLOWED_SMTP_KEYS}) 의 합집합이 아닌 키는 즉시
   * 거부한다.
   *
   * <p>키를 두 그룹(AI+임베딩 / SMTP)으로 나눠 각자의 검증·마스킹·암호화 로직에 위임한다 — 그
   * 로직은 Task 5 이전에 {@link #updateSettings} 와 옛 테넌트 평면 SMTP 쓰기가 쓰던 것과
   * <b>동일한 코드</b>다({@link #applyPlatformAiEmbeddingSettings}, {@link #applyPlatformSmtpSettings}
   * 로 이름만 옮겼다). 플랫폼 경로가 검증을 다시 구현하면 두 평면(테넌트/플랫폼)의 "유효한 값"
   * 판정이 갈라진다.
   */
  @Transactional
  public void updatePlatformSettings(Map<String, String> settings, Long userId) {
    requirePlatformPlane();
    rejectNullValues(settings);
    for (String key : settings.keySet()) {
      if (!ALLOWED_AI_KEYS.contains(key)
          && !ALLOWED_EMBEDDING_KEYS.contains(key)
          && !ALLOWED_SMTP_KEYS.contains(key)) {
        throw new IllegalArgumentException("허용되지 않는 설정 키: " + key);
      }
    }

    Map<String, String> aiEmbedding =
        settings.entrySet().stream()
            .filter(e -> ALLOWED_AI_KEYS.contains(e.getKey()) || ALLOWED_EMBEDDING_KEYS.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    Map<String, String> smtp =
        settings.entrySet().stream()
            .filter(e -> ALLOWED_SMTP_KEYS.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    if (!aiEmbedding.isEmpty()) applyPlatformAiEmbeddingSettings(aiEmbedding, userId);
    if (!smtp.isEmpty()) applyPlatformSmtpSettings(smtp, userId);
  }

  /**
   * {@link #updateSettings} 가 P7-b 이전까지 하던 일 그대로다(AI+임베딩 검증·마스킹·암호화 후
   * {@code system_settings} 갱신) — 이름만 "테넌트 쓰기"에서 "플랫폼 쓰기"로 바뀌었다. 지금은
   * {@link #updatePlatformSettings} 만 부른다.
   */
  private void applyPlatformAiEmbeddingSettings(Map<String, String> settings, Long userId) {
    boolean hasMaskedApiKey = isMaskedApiKey(settings.get("ai.api_key"));
    boolean hasMaskedCliToken = isMaskedApiKey(settings.get("ai.cli_oauth_token"));
    boolean hasMaskedEmbeddingKey = isMaskedApiKey(settings.get("embedding.api_key"));

    // Skip validation and save for masked values (unchanged by user)
    Map<String, String> filtered =
        settings.entrySet().stream()
            .filter(e -> !(hasMaskedApiKey && "ai.api_key".equals(e.getKey())))
            .filter(e -> !(hasMaskedCliToken && "ai.cli_oauth_token".equals(e.getKey())))
            .filter(e -> !(hasMaskedEmbeddingKey && "embedding.api_key".equals(e.getKey())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    validateValues(filtered);
    validateEmbeddingConsistency(filtered);

    Map<String, String> toUpdate = encryptSecrets(filtered);

    if (!toUpdate.isEmpty()) {
      settingsRepository.updateSettings(toUpdate, userId);
    }
  }

  /**
   * 테넌트 오버라이드를 지운다 = 상속 복귀. {@code tenant_settings} 에 행이 있으면 지우고, 없으면
   * 아무 일도 하지 않는다 — "이미 상속 중"은 오류가 아니라 멱등한 성공이다(호출부인
   * {@code DELETE /api/v1/settings/{key}} 는 있든 없든 204 를 돌려준다).
   */
  @Transactional
  public void clearOverride(String key) {
    tenantSettingsRepository.delete(key);
  }

  /**
   * 값이 {@code null} 인 키를 <b>검증 계층에서</b> 거부한다.
   *
   * <p>그냥 흘려보내면 저장 계층까지 내려가 NPE 가 되어 <b>클라이언트 오류가 500 으로 보고된다</b>:
   * 테넌트 경로는 {@link TenantSettingsRepository#upsert} 의 {@code Objects.requireNonNull} 에서,
   * 플랫폼 경로는 {@link #encryptIfSecret} 의 {@code value.isBlank()} 에서 터진다.
   * {@code validateValues} 는 {@code ai.model} 같은 free-form 키를 그냥 통과시키므로
   * {@code {"ai.model": null}} 이 실제로 거기까지 도달한다.
   *
   * <p>저장 계층의 fail-fast 자체는 옳다 — "오버라이드 삭제는 {@code delete} 로"라는 통로 분리를
   * 강제한다. 여기서 미리 거르는 것은 그 fail-fast 를 없애려는 게 아니라, <b>같은 실수가 400 으로
   * 보고되게</b> 하려는 것이다.
   */
  private static void rejectNullValues(Map<String, String> settings) {
    for (var entry : settings.entrySet()) {
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("설정 값은 null 일 수 없습니다: " + entry.getKey());
      }
    }
  }

  /**
   * 플랫폼 평면에서 호출됐는지 <b>서비스 레벨에서</b> 확인한다.
   *
   * <p>이 밴드는 "컨트롤러가 아니라 서비스에서 막는다 — 애노테이션
   * 하나만 지우면 뚫리는 방식보다 안전하다"고 선언해 놓고, 정작 <b>전 테넌트가 공유하는 18행을
   * 쓰는 가장 위험한 메서드</b>는 컨트롤러 애노테이션과 {@code PlatformPlaneFilter} 에만 기대고
   * 있었다. {@code /api/v1/**} 경로에 이 메서드를 부르는 호출자가 하나 생기면 필터는 그 경로를
   * 보지 않고 메서드는 평면을 묻지 않는다.
   *
   * <p><b>평면은 인증 "타입"으로 판정한다</b>({@link PlatformAuthentication} 인가) — 표식의 부재로
   * 판정하지 않는다(P7-a 의 양방향 함정). 인증이 <b>아예 없는</b> 경우는 통과시킨다: 테넌트 HTTP
   * 요청은 {@code JwtAuthenticationFilter} 가 반드시 인증을 채우므로 "인증 없음"은 테넌트일 수
   * 없고, 배경 잡·부트스트랩·서비스 직접 호출이 여기 해당한다. 없음을 거부로 바꾸면
   * {@code TenantContext.get() != null} 로 평면을 판정하다 실패했던 것과 같은 종류의 오판이 된다.
   */
  private void requirePlatformPlane() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && !PlatformAuthentication.isCurrent()) {
      throw new AccessDeniedException("플랫폼 설정은 플랫폼 운영자만 변경할 수 있습니다");
    }
  }

  private static boolean isMaskedApiKey(String value) {
    return value != null && value.startsWith("****");
  }

  /**
   * 비밀 값(ai.api_key, ai.cli_oauth_token, embedding.api_key, smtp.password)은 저장 전 암호화한다.
   * embedding.api_key 는 Ollama 로컬 등 키가 불필요한 경우, smtp.password 는 인증 없는 릴레이를 쓰는 경우
   * 빈 문자열일 수 있으므로, 빈 값은 암호화하지 않고 그대로 둔다(빈 ciphertext 복호화 실패 방지).
   *
   * <p><b>암호화 판정은 여기 하나뿐이다.</b> P7-c1 이전에는 {@code smtp.password} 암호화가
   * {@code applyPlatformSmtpSettings} 안에 따로 있었고(같은 판정의 두 번째 자리), 테넌트 평면이
   * 열렸을 때 그 사본이 따라오지 않아 <b>평문이 저장됐다</b>. 새 비밀 키는 {@link #SECRET_KEYS} 와
   * 이 메서드 두 곳만 고친다.
   */
  private String encryptIfSecret(String key, String value) {
    // 암호화 대상인지는 **오직 SECRET_KEYS 가** 정한다 — 키 이름을 하나씩 나열하면 새 비밀 키가
    // 추가될 때 이 목록이 따라오지 않는다(이 밴드가 고친 결함이 정확히 그 형태였다).
    if (!SECRET_KEYS.contains(key)) return value;

    // 여기서 키를 다시 보는 것은 "암호화할까"가 아니라 "빈 값도 암호화할까"뿐이다.
    // ai.api_key 는 빈 값을 validateValues 가 이미 거부하고, ai.cli_oauth_token 은 빈 값도
    // 암호화하던 기존 동작을 유지한다(SettingsServiceCliTokenTest 가 그 왕복을 고정한다).
    boolean encryptEvenIfBlank = "ai.api_key".equals(key) || "ai.cli_oauth_token".equals(key);
    return !encryptEvenIfBlank && value.isBlank() ? value : encryptionService.encrypt(value);
  }

  /**
   * 맵 전체를 {@link #encryptIfSecret} 에 통과시킨다. 비밀 키가 아니면 값이 그대로 나오므로
   * <b>어떤 맵에 적용해도 무해</b>하고, 그래서 두 쓰기 경로가 "이 그룹은 비밀이 들어올 수 있는
   * 그룹인가"를 프리픽스로 미리 판단할 필요가 없다 — 그 판단이 바로 이 밴드가 고친 결함의 모양이다.
   */
  private Map<String, String> encryptSecrets(Map<String, String> settings) {
    return settings.entrySet().stream()
        .collect(
            Collectors.toMap(Map.Entry::getKey, e -> encryptIfSecret(e.getKey(), e.getValue())));
  }

  /**
   * {@code ai.api_key} 는 {@link SettingsOverridePolicy} 화이트리스트에 없는 플랫폼 잠금 키다 —
   * {@link #getValue} 가 해석기를 타더라도 {@link #resolveOverrides} 가 항상 빈 결과를 주므로 여기
   * 결과는 바뀌지 않는다. 장래에 {@code ai.api_key} 가 BYO(Bring Your Own) 키 정책으로 테넌트에
   * 열리면(정책 화이트리스트 추가), 이 메서드가 "어느 테넌트의 키를 복호화하는가"를 다시 따져야
   * 하는 지점이 된다.
   */
  @Transactional(readOnly = true)
  public Optional<String> getDecryptedApiKey() {
    return getValue("ai.api_key").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
  }

  /** {@code ai.cli_oauth_token} 도 플랫폼 잠금 키다 — 근거는 {@link #getDecryptedApiKey} 와 같다. */
  @Transactional(readOnly = true)
  public Optional<String> getDecryptedCliOauthToken() {
    return getValue("ai.cli_oauth_token").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
  }

  /**
   * 임베딩 provider 인증용 복호화된 API 키. OpenAI 등 인증이 필요한 provider 에서만 사용하며, Ollama(로컬)는 빈 값이라
   * empty 를 반환한다. 키는 절대 ai-agent 로 내려보내지 않고 api 내부(EmbeddingProviderFactory)에서만 쓴다.
   *
   * <p>{@code embedding.*} 4키도 화이트리스트에 없는 플랫폼 잠금 키다(모델 교체가 벡터 차원을 바꿔
   * 기존 임베딩을 무효화하므로 테넌트별로 다를 수 없다) — 근거는 {@link #getDecryptedApiKey} 와 같다.
   */
  @Transactional(readOnly = true)
  public Optional<String> getDecryptedEmbeddingApiKey() {
    return getValue("embedding.api_key").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
  }


  /**
   * P7-b 이전 테넌트 평면 SMTP 쓰기가 하던 로직 그대로다. 이름만 "플랫폼 쓰기 본체"로 옮겼고,
   * {@link #updatePlatformSettings}(Task 6) 가 테넌트 평면 가드 없이 바로 이 메서드를 부른다.
   *
   * <p>검증·센티널은 {@link #normalizeSmtpPayload} 로, 암호화는 {@link #encryptSecrets} 로 빠졌고
   * 여기 남은 것은 <b>플랫폼 전용</b> 두 가지다: 플랫폼 화이트리스트 판정과
   * {@code system_settings} 쓰기.
   */
  private void applyPlatformSmtpSettings(Map<String, String> settings, Long userId) {
    for (String key : settings.keySet()) {
      if (!ALLOWED_SMTP_KEYS.contains(key)) {
        throw new IllegalArgumentException("허용되지 않는 SMTP 설정 키: " + key);
      }
    }

    Map<String, String> toUpdate = encryptSecrets(normalizeSmtpPayload(settings));

    if (!toUpdate.isEmpty()) {
      settingsRepository.updateSettings(toUpdate, userId);
    }
  }

  /**
   * SMTP 쓰기의 <b>평면 공통</b> 부분: 포트 범위 검증 · 마스크 센티널 제거.
   * {@link #applyPlatformSmtpSettings}(→ {@code system_settings})와 {@link #updateSettings}
   * (→ {@code tenant_settings})가 함께 부른다.
   *
   * <p><b>쓰기는 일부러 여기 없다.</b> 두 평면은 저장소가 다르고, 저장 대상까지 공유하면 한쪽이
   * 바뀔 때 다른 쪽이 조용히 따라가거나 조용히 어긋난다. 실제로 이 결함 자체가 "플랫폼 경로에만
   * 있던 검증·암호화가 새로 열린 테넌트 경로에 없었던 것"이다 — 공유 범위를 값 변환까지로 좁히고,
   * 어느 테이블에 쓰는지는 호출부가 각자 정한다.
   *
   * <p><b>센티널은 현재 값과 비교하지 않고 페이로드에서 떨어뜨린다.</b> {@code ****} 로 시작하는
   * 값은 "화면이 받은 마스크를 그대로 돌려보냈다 = 사용자가 안 고쳤다"는 뜻이라 현재 값이 무엇이든
   * 결론이 같다. 비교하려면 현재 값을 읽어야 하고, 그러려면 이 헬퍼가 <b>어느 저장소를 읽을지</b>
   * 알아야 한다 — 위에서 밀어낸 평면 지식이 읽기 쪽 문으로 다시 들어온다.
   *
   * <p><b>암호화는 여기 없다.</b> 여기서 하면 "암호화 대상인가"의 답이 키의 비밀 여부가 아니라
   * <b>SMTP 프리픽스에 속하는가</b>가 되어, SMTP 가 아닌 비밀 키가 테넌트에 열리는 순간 평문으로
   * 저장된다 — {@link SettingsOverridePolicy} javadoc 이 적어 둔 BYO 키 정책이 그 트리거다
   * ({@code ai.api_key} 가 화이트리스트 한 줄로 열린다). 암호화는 두 호출부가 저장 직전에
   * {@link #encryptSecrets} 로 <b>맵 전체</b>에 적용한다.
   *
   * <p>입력 맵은 건드리지 않고 새 맵을 만든다({@code Map.of} 로 온 불변 맵이 흔하다).
   */
  private Map<String, String> normalizeSmtpPayload(Map<String, String> settings) {
    // smtp.port 범위 검증 — 1~65535 범위를 벗어나면 400 Bad Request
    if (settings.containsKey("smtp.port")) {
      String portStr = settings.get("smtp.port");
      try {
        int port = Integer.parseInt(portStr);
        if (port < 1 || port > 65535) {
          throw new IllegalArgumentException("SMTP 포트 번호는 1에서 65535 사이여야 합니다. 입력값: " + port);
        }
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("SMTP 포트 번호가 유효하지 않습니다: " + portStr);
      }
    }

    Map<String, String> normalized = new HashMap<>();
    settings.forEach(
        (key, value) -> {
          // 마스크 센티널이면 그 키만 통째로 버린다 — 저장하면 살아 있는 비밀번호가 문자열
          // "****abcd" 로 덮어써져 "아무것도 안 바꿨는데 메일이 안 나간다"가 된다.
          if ("smtp.password".equals(key) && isMaskedApiKey(value)) return;
          normalized.put(key, value);
        });
    return normalized;
  }

  /**
   * 실제 메일 발송이 쓰는 SMTP 접속 정보. <b>테넌트 오버라이드를 해석</b>하고 비밀값을
   * <b>복호화</b>해서 돌려준다.
   *
   * <p>P7-c1 이전 이 자리에는 "SMTP 6키는 전부 플랫폼 잠금이라 해석기를 타지 않아도 무해하지만,
   * 장래에 SMTP 가 테넌트별로 열리면 이 메서드도 해석기를 타도록 바뀌어야 한다"고 적혀 있었다.
   * Task 1 이 6키를 열었고 이 태스크가 그 전환을 마쳤다 — 이제 테넌트가 저장한 SMTP 로 실제
   * 메일이 나간다. 그 전까지는 저장도 되고 화면도 {@code overridden=true} 라고 보고하는데 발송만
   * 플랫폼 자격증명으로 나가는, <b>저장·표시·동작 셋 중 둘만 맞는 무동작</b>이었다.
   *
   * <p><b>화면용 읽기와 발송용 읽기는 요구가 정반대다.</b> {@link #getResolvedByPrefix} 는 같은
   * 데이터를 <b>마스킹</b>해서 내보내고(응답에 평문도 암호문도
   * 실리면 안 된다), 이 메서드는 <b>복호화</b>해서 내보낸다(SMTP 인증에 평문이 필요하다). 그래서
   * 마스킹을 타는 {@link #getResolvedByPrefix} 를 재사용할 수 없고, 마스킹 없는 해석 경로인
   * {@link #getAsMap} 위에 복호화를 얹는다.
   *
   * <p>복호화가 오버라이드 값에도 걸려야 하는 이유: Task 2 가 테넌트 오버라이드
   * {@code smtp.password} 도 암호화해 저장하게 만들었다. 해석기만 태우고 복호화를 플랫폼 값에만
   * 남겨 두면 테넌트 SMTP 인증이 <b>암호문으로</b> 시도돼 발송이 조용히 실패한다.
   *
   * <p>{@code @Transactional} 을 떼지 말 것 — {@link #getAsMap} 을 자기 호출로 부르므로 프록시를
   * 지나지 않는다. {@code tenant_settings}(RLS) 조회에 GUC 를 주입하는 트랜잭션은 <b>이 애노테이션</b>이 연다.
   */
  @Transactional(readOnly = true)
  public Map<String, String> getSmtpConfig() {
    return getAsMap("smtp").entrySet().stream()
        .collect(
            Collectors.toMap(Map.Entry::getKey, e -> decryptIfSecret(e.getKey(), e.getValue())));
  }

  /**
   * 복호화 판정의 <b>값 단위</b> 형태 — {@link #encryptIfSecret} 의 역방향이고 판정 기준도 같은
   * {@link #SECRET_KEYS} 다. 키 이름을 따로 나열하면 새 비밀 키가 추가될 때 쓰기만 암호화하고
   * 읽기는 암호문을 그대로 흘리는 비대칭이 생긴다.
   *
   * <p><b>빈 값은 복호화하지 않는다.</b> {@code smtp.password} 는 인증 없는 릴레이에서 빈 문자열일
   * 수 있고, {@link #encryptIfSecret} 이 그 값을 암호화하지 않고 그대로 두므로 빈 ciphertext 를
   * 복호화하려 들면 SMTP 설정 조회 자체가 터진다(= 발송 전체 정지). 플랫폼 경로에 있던
   * {@code !isBlank()} 가드를 그대로 옮겨 온 것이다.
   */
  private String decryptIfSecret(String key, String value) {
    if (!SECRET_KEYS.contains(key)) return value;
    return value == null || value.isBlank() ? "" : encryptionService.decrypt(value);
  }

  private void validateValues(Map<String, String> settings) {
    settings.forEach(
        (key, value) -> {
          switch (key) {
            case "ai.max_turns" -> {
              int v = Integer.parseInt(value);
              if (v < 1 || v > 50) throw new IllegalArgumentException("최대 턴 수는 1에서 50 사이여야 합니다");
            }
            case "ai.temperature" -> {
              double v = Double.parseDouble(value);
              if (v < 0 || v > 1)
                throw new IllegalArgumentException("Temperature는 0.0에서 1.0 사이여야 합니다");
            }
            case "ai.max_tokens" -> {
              int v = Integer.parseInt(value);
              if (v < 1 || v > 65536)
                throw new IllegalArgumentException("최대 토큰 수는 1에서 65536 사이여야 합니다");
            }
            case "ai.session_max_tokens" -> {
              int v = Integer.parseInt(value);
              // 하한은 web 의 검증(10,000~200,000)과 반드시 같아야 한다. 예전 값은 1000 이었고,
              // 그 차이는 이 키를 아무도 저장할 수 없던 동안(시드 행 없음 + UPDATE-only) 도달
              // 불가라 드러나지 않았다. 저장소를 upsert 로 고쳐 경로가 열리는 순간, 운영자가
              // 5000 을 넣으면 테넌트 화면이 그 값으로 시드되고 web 하한 10000 에 걸려 —
              // 사용자가 그 필드를 건드리지도 않았는데 다른 필드 저장까지 전부 막힌다.
              // 하한을 올리는 방향이라 회귀 위험은 0 이다: 1000~9999 를 저장한 사람이 존재할 수 없다.
              if (v < 10000 || v > 200000)
                throw new IllegalArgumentException("세션 최대 토큰 수는 10000에서 200000 사이여야 합니다");
            }
            case "ai.system_prompt" -> {
              if (value == null || value.isBlank())
                throw new IllegalArgumentException("시스템 프롬프트는 비어있을 수 없습니다");
            }
            case "ai.api_key" -> {
              if (value == null || value.isBlank())
                throw new IllegalArgumentException("API 키는 비어있을 수 없습니다");
            }
            case "ai.cli_oauth_token" -> {
              /* CLI OAuth 토큰은 비어있을 수 있음 (구독 미사용 시) */
            }
            case "ai.agent_type" -> {
              // opencode 추가: opencode 는 배포 환경의 opencode auth 에 의존하므로 별도 자격증명 불필요
              if (!Set.of("sdk", "cli", "cli-api", "opencode").contains(value))
                throw new IllegalArgumentException("에이전트 유형은 sdk, cli, cli-api, opencode 중 하나여야 합니다");
            }
            case "embedding.provider" -> {
              if (!Set.of("OLLAMA", "VOYAGE", "OPENAI").contains(value))
                throw new IllegalArgumentException(
                    "임베딩 provider 는 OLLAMA, VOYAGE, OPENAI 중 하나여야 합니다");
            }
            default -> {
              /* ai.model is a free-form string, validated by frontend dropdown */
            }
          }
        });
  }

  /**
   * 임베딩 설정의 항목 간 정합성을 검증한다 (이슈 #322, #323).
   *
   * <p>키를 하나씩 보는 {@link #validateValues}로는 "provider 는 OPENAI 인데 base_url 이 Ollama 주소"
   * 같은 조합 오류를 잡을 수 없다. 잘못된 조합이 저장되면 실패가 설정 화면이 아니라 한참 뒤
   * {@code EmbeddingProviderFactory} 런타임에야 드러나므로, 저장 시점에 막는다.
   *
   * <p><b>유효값 해석 규칙</b>: 페이로드에 <b>키가 없으면</b> 저장된 값으로 폴백하고, <b>키가 있으면
   * 빈 문자열이라도 그 값을 그대로</b> 쓴다. 마스킹된 api_key 는 호출부에서 이미 제거되므로 "키 없음"
   * = "기존 키 유지"로 해석되어, 저장된 키가 있는데 페이로드에 없다는 이유로 거부하는 회귀가 나지 않는다.
   * 반대로 사용자가 명시적으로 비운 빈 문자열은 그대로 "빈 값"으로 취급해 거부한다.
   */
  private void validateEmbeddingConsistency(Map<String, String> settings) {
    // 임베딩 키가 하나도 없는 저장(예: AI 탭 저장)은 검증 대상이 아니다.
    if (settings.keySet().stream().noneMatch(ALLOWED_EMBEDDING_KEYS::contains)) return;

    String provider = effectiveValue(settings, "embedding.provider").orElse("OLLAMA");
    String model = effectiveValue(settings, "embedding.model").orElse("");
    String baseUrl = effectiveValue(settings, "embedding.base_url").orElse("");

    // 모델/base_url 은 어떤 provider 든 비어 있으면 안 된다 (페이로드에 명시된 경우에 한해 검사).
    if (settings.containsKey("embedding.model") && model.isBlank())
      throw new IllegalArgumentException("임베딩 모델은 비어있을 수 없습니다");
    if (settings.containsKey("embedding.base_url") && baseUrl.isBlank())
      throw new IllegalArgumentException("임베딩 Base URL 은 비어있을 수 없습니다");

    // base_url 형식 — http/https 스킴과 호스트를 갖춘 절대 URL 이어야 한다.
    if (!baseUrl.isBlank() && embeddingUrlScheme(baseUrl).isEmpty())
      throw new IllegalArgumentException(
          "임베딩 Base URL 은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다: " + baseUrl);

    if (!"OPENAI".equals(provider)) return;

    // OPENAI 는 공개 API/프록시 모두 TLS 를 쓴다. http 주소가 남아 있다는 것은 Ollama 등 다른
    // provider 주소가 그대로 남은 불일치 신호이므로 거부한다 (평문 http 자체 호스팅 프록시는 미지원).
    if (!baseUrl.isBlank() && !"https".equals(embeddingUrlScheme(baseUrl).orElse("")))
      throw new IllegalArgumentException(
          "OpenAI 임베딩 provider 의 Base URL 은 https 주소여야 합니다. 현재 값: "
              + baseUrl
              + " (provider 를 변경했다면 Base URL 도 함께 변경하세요)");

    // OPENAI 는 Bearer 인증 필수 — 저장된 키도 없고 새 키도 없으면 저장을 막는다.
    boolean hasStoredKey =
        settingsRepository.getValue("embedding.api_key").filter(v -> !v.isBlank()).isPresent();
    String submittedKey = settings.get("embedding.api_key");
    boolean keyAvailable =
        submittedKey != null ? !submittedKey.isBlank() : hasStoredKey;
    if (!keyAvailable) throw new IllegalArgumentException("OpenAI 임베딩 provider 에는 API 키가 필요합니다");
  }

  /** 페이로드에 키가 있으면 그 값(빈 문자열 포함), 없으면 저장된 값을 반환한다. */
  private Optional<String> effectiveValue(Map<String, String> settings, String key) {
    if (settings.containsKey(key)) return Optional.ofNullable(settings.get(key));
    return settingsRepository.getValue(key);
  }

  /** base_url 의 http/https 스킴을 반환한다. 절대 URL 이 아니거나 호스트가 없으면 empty. */
  private Optional<String> embeddingUrlScheme(String baseUrl) {
    try {
      java.net.URI uri = java.net.URI.create(baseUrl.trim());
      String scheme = uri.getScheme();
      if (uri.getHost() == null || scheme == null) return Optional.empty();
      String lower = scheme.toLowerCase(java.util.Locale.ROOT);
      return "http".equals(lower) || "https".equals(lower) ? Optional.of(lower) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}

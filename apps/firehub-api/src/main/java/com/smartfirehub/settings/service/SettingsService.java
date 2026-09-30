package com.smartfirehub.settings.service;

import static com.smartfirehub.settings.service.SettingsOverridePolicy.planeOf;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.repository.SettingsRepository;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

  /**
   * 암호화 저장되는 비밀 키의 집합. 마스킹 판정의 <b>단일 출처</b>다.
   *
   * <p>{@code smtp.password} 가 빠져 있었다. 플랫폼 SMTP 쓰기는 이 키를 암호화해
   * 저장하는데 {@link #maskSecret} 이 그것을 모르면 {@code getAll}/{@code getResolvedByPrefix} 가
   * <b>암호문을 그대로</b> 내보낸다(당시 SMTP 전용 읽기 메서드만 별도로 마스킹하고 있었다 — 즉 이
   * 목록은 이미 한 번 어긋난 상태였다). 새 비밀 키를 추가할 때는 <b>여기만</b> 고친다.
   */
  private static final Set<String> SECRET_KEYS = Set.of("smtp.password");

  private final SettingsRepository settingsRepository;
  private final EncryptionService encryptionService;
  private final TenantSettingsRepository tenantSettingsRepository;

  /**
   * 프리픽스에 속한 플랫폼 행 중 플랫폼 평면이 있는 키만. 테넌트 네임스페이스({@code "ai"}·{@code "smtp"})면
   * 조회 자체를 생략한다.
   */
  private List<SettingResponse> platformRows(String prefix) {
    if (!SettingsOverridePolicy.mayHavePlatformRows(prefix)) return List.of();
    return settingsRepository.findByPrefix(prefix).stream()
        // system_settings 행을 값으로 쓰는 평면은 UNKNOWN 뿐이다(TENANT_ONLY·EXTERNAL_OWNER 는 플랫폼 행을 읽지 않는다).
        .filter(s -> planeOf(s.key()) == SettingsOverridePolicy.Plane.UNKNOWN)
        .toList();
  }

  /**
   * 읽기 응답에서 비밀 값 자리에 싣는 <b>고정 표식</b>. "값이 저장돼 있다"는 사실만 알린다.
   *
   * <p>예전에는 {@code EncryptionService.maskValue} 로 {@code ****} + <b>평문 끝 4자</b>를
   * 내보냈다(#725). 4자 비밀번호는 전체가, 5자는 80% 가 응답에 실려 브라우저·프록시 로그에
   * 남았는데, 화면은 이 값을 "빈 문자열인가"로만 쓴다 — 아무도 쓰지 않는 누출이었다.
   * {@code maskValue} 자체는 API 연결·임베딩 키 표시가 계속 쓰므로 건드리지 않는다.
   */
  static final String SECRET_MASK = "****";

  /**
   * 비밀값은 고정 표식({@link #SECRET_MASK})으로 바꿔 내보낸다. 평문도, 평문 조각도, 암호문도
   * 응답에 실리지 않는다.
   *
   * <p>"마스킹이 일어났는가"를 <b>키로</b> 판정한다. 예전에는 {@code maskIfSecret} 의 반환 참조가
   * 입력과 같은지(`==`)로 판정했는데, 그 성질은 정작 노리던 자리에서 성립하지 않았다 — 비밀 키의
   * 값이 빈 문자열이면 {@code maskIfSecret} 이 <b>리터럴</b> {@code ""} 를 돌려주고 JDBC 가 만든
   * 문자열과는 `==` 가 거짓이라 DTO 를 어차피 다시 만든다. 관측 결과는 같지만 두 메서드가 성립하지
   * 않는 성질을 계약으로 붙들고 있었고, 그 결합은 {@code maskIfSecret} 이 언젠가 비밀 아닌 키에도
   * 손을 대는 순간 <b>조용히</b> 전 행 재생성으로 바뀐다.
   */
  private SettingResponse maskSecret(SettingResponse setting) {
    if (!SECRET_KEYS.contains(setting.key())) return setting;
    String masked = maskIfSecret(setting.key(), setting.value());
    return new SettingResponse(setting.key(), masked, setting.description(), setting.updatedAt());
  }

  /**
   * 마스킹 판정의 <b>값 단위</b> 형태. {@link #maskSecret}(플랫폼 행)과
   * {@link #getResolvedByPrefix}(테넌트 오버라이드 값)가 공유한다 — 오버라이드 쪽에 판정을 복사하면
   * {@link #SECRET_KEYS} 에 키가 추가될 때 한쪽만 반영되어 다시 암호문이 새어 나간다.
   *
   * <p>비밀 키가 아니면 값을 그대로 돌려주므로 <b>어떤 키에 적용해도 무해</b>하다 —
   * {@link #encryptSecrets} 와 같은 성질이라 호출부가 미리 키를 걸러낼 필요가 없다.
   */
  private String maskIfSecret(String key, String value) {
    if (!SECRET_KEYS.contains(key)) return value;
    // 복호화하지 않는다 — 읽기 경로는 평문을 만들 이유가 없고, 표식은 값과 무관하게 같다(#725).
    // 빈 값(인증 없는 릴레이)은 빈 문자열 그대로: 화면이 "저장된 비밀번호 없음"으로 읽는다.
    return value == null || value.isBlank() ? "" : SECRET_MASK;
  }

  /**
   * 설정 값 해석. {@code tenant_settings} 에 값이 있으면 그 값, 없으면 {@code system_settings}.
   *
   * <p><b>테넌트 전용 키({@link SettingsOverridePolicy.Plane#TENANT_ONLY})는 다르다</b> — 테넌트 값이
   * 없으면 {@code system_settings} 가 아니라 코드 기본값({@link AiBehaviorDefaults})이다. 그래서
   * AI 동작 6키는 항상 값이 있는 {@code Optional} 을 돌려준다. 코드 기본값이 없는 SMTP 6키(#712)는
   * 테넌트 값이 없으면 empty(미설정)다. 테넌트 컨텍스트가 없는 호출(배경 경로)도 같다 — 플랫폼 행은
   * 어느 경우에도 읽지 않는다.
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
    rejectExternalOwnerKey(key);
    return switch (planeOf(key)) {
      // 테넌트 값 → 코드 기본값. system_settings 에 남은 옛 행이 있어도 보지 않는다. 기본값이 없는
      // 키(SMTP 6키·옛 ai.*)는 empty = 미설정이다.
      case TENANT_ONLY ->
          tenantValue(key).or(() -> Optional.ofNullable(AiBehaviorDefaults.defaultOf(key)));
      case EXTERNAL_OWNER -> throw externalOwnerKey(key); // rejectExternalOwnerKey 가 이미 막는다
      // UNKNOWN: 테넌트 쓰기 가능 키(SMTP·AI 동작)가 전부 TENANT_ONLY 라 이 평면에는
      // 테넌트 값이 존재할 수 없다(tenantValue 는 화이트리스트 밖이라 항상 empty 였다). 플랫폼 행만 본다.
      default -> settingsRepository.getValue(key);
    };
  }

  /**
   * 이 키의 테넌트 저장 값. 컨텍스트가 없으면(배경 잡 경로) DB 조회조차 하지 않는다 — "컨텍스트
   * 없음 = 오버라이드 없음" 계약이다. 화이트리스트를 읽기에서 다시 보는 이유는 {@link #getValue}
   * javadoc 참고(키를 플랫폼으로 회수하면 즉시 효력을 갖는다).
   */
  private Optional<String> tenantValue(String key) {
    if (TenantContext.get() == null || !SettingsOverridePolicy.isTenantOverridable(key)) {
      return Optional.empty();
    }
    return tenantSettingsRepository.findValue(key);
  }

  /**
   * <b>전용 서비스 소유 키({@link SettingsOverridePolicy.Plane#EXTERNAL_OWNER})를 범용 경로(읽기·쓰기
   * 전부)로 못 쓰게 막는 단일 관문.</b> {@link #getValue}·{@link #updateSettings} 가 공유하고, {@link #resolveOverridesByPrefix} 도 같은 판정으로 뺀다.
   *
   * <p>읽기뿐 아니라 쓰기도 막는 이유: 값이 JSON 이고 비밀이 <b>하위 필드</b>에 있어, 범용 쓰기가
   * 받으면 {@link AiCredentialService} 의 유형별 검증·하위 필드 암호화·비밀 필수 규칙을 전부
   * 건너뛴 미검증 문서가 저장된다. 화이트리스트에도 없지만, 그것이 실수로 넓어져도 이 관문이
   * 별도로 막는다.
   */
  private static void rejectExternalOwnerKey(String key) {
    if (planeOf(key) == SettingsOverridePolicy.Plane.EXTERNAL_OWNER) {
      throw externalOwnerKey(key);
    }
  }

  private static IllegalArgumentException externalOwnerKey(String key) {
    return new IllegalArgumentException(
        "AI 자격증명은 범용 설정 경로로 읽거나 쓸 수 없습니다(비밀이 하위 필드에 있다). AiCredentialService 를 쓰세요: "
            + key);
  }

  /**
   * <b>교집합이 아니라 합집합.</b> {@code system_settings} 에 행이 없는 오버라이드 허용 키(예:
   * {@code ai.session_max_tokens} — 어떤 마이그레이션도 이 키를 시드하지 않았고
   * {@code SettingsRepository.updateSettings} 가 UPDATE-only 라 오늘 이 키에 쓰면 0행이 갱신된다)는
   * 플랫폼 맵을 먼저 만들고 그 키 집합만 오버라이드 조회에 넘기면 <b>절대 드러나지 않는다</b>.
   * 오버라이드 자체를 프리픽스로 통째로 가져와 덮어써야, 플랫폼 행이 없는 키의 오버라이드도 결과에
   * 나타난다. AI 채팅·프로액티브 잡이 실제로 읽는 경로라 이 구멍은 "오버라이드를 저장했는데 실제
   * 호출은 여전히 하드코딩 폴백을 쓴다"는 형태로 조용히 발현한다.
   *
   * <p><b>{@code ai.*} 는 플랫폼 행을 읽지 않는다.</b> AI 동작 키는 코드 기본값
   * ({@link #aiDefaultsForPrefix})을 바닥에 깔고 테넌트 값으로 덮는다 — 그래서 {@code getAsMap("ai")}
   * 는 테넌트가 아무것도 저장하지 않았어도 6키를 전부 담는다. {@code ai.credential} 은 어느
   * 쪽에도 실리지 않는다(실리면 비밀 하위 필드 암호문이 새어 나간다).
   *
   * <p><b>{@code smtp.*} 도 플랫폼 행을 읽지 않고(#712), 코드 기본값도 없다.</b> 그래서
   * {@code getAsMap("smtp")} 는 테넌트가 저장한 키만 담고, 미설정 워크스페이스·컨텍스트 없는 호출은
   * 빈 맵이다. 빈 값을 채워 넣지 않는 이유: 소비자는 키가 <b>없을 때</b> 안전한 기본값
   * ({@code smtp.starttls → true}, {@code smtp.port → 587})을 쓰는데, 빈 문자열이 들어 있으면
   * STARTTLS 가 조용히 꺼진다.
   */
  @Transactional(readOnly = true)
  public Map<String, String> getAsMap(String prefix) {
    // system_settings.value 컬럼은 nullable이므로 null value가 있으면 Collectors.toMap이 NPE를 발생시킨다.
    // null value는 빈 문자열로 대체하고, 중복 키 발생 시 나중 값(b)을 사용하는 merge function을 지정한다.
    Map<String, String> resolved =
        platformRows(prefix).stream()
            .collect(
                Collectors.toMap(
                    SettingResponse::key,
                    s -> s.value() != null ? s.value() : "",
                    (a, b) -> b,
                    HashMap::new));
    resolved.putAll(aiDefaultsForPrefix(prefix));
    resolved.putAll(resolveOverridesByPrefix(prefix));
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
   * <p><b>플랫폼 행은 {@link #maskSecret} 을 지난다.</b> 이 경로만 빠뜨리면 비밀 키(오늘은
   * {@code smtp.password} 하나 — {@link #SECRET_KEYS}) 조회가 <b>AES 암호문을 그대로</b> 내보낸다 — {@link #SECRET_KEYS} javadoc 이
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
   *
   * <p><b>{@code ai.*} 는 플랫폼 행을 읽지 않는다</b>({@link #getAsMap} javadoc 과 같은 이유).
   * AI 동작 6키는 항상 전부 나오고, {@code value} 는 테넌트 값 또는 코드 기본값,
   * {@code overridden} 은 "테넌트가 저장한 값이 있음", {@code description}/{@code updatedAt} 은
   * {@code null} 이다 — 화면은 이 플래그로 "저장된 값"과 "기본값"을 구분한다.
   *
   * <p><b>{@code smtp.*} 는 테넌트가 저장한 키만 나온다(#712).</b> 플랫폼 행도 코드 기본값도 없으므로
   * 미설정 워크스페이스는 빈 목록이고, 나오는 행은 전부 {@code overridden=true}·
   * {@code tenantEditable=true}·{@code description/updatedAt=null} 이다. {@code smtp.password} 는
   * 마스킹된다.
   */
  @Transactional(readOnly = true)
  public List<ResolvedSettingResponse> getResolvedByPrefix(String prefix) {
    Map<String, SettingResponse> platformByKey =
        platformRows(prefix).stream()
            .map(this::maskSecret)
            .collect(Collectors.toMap(SettingResponse::key, s -> s));
    Map<String, String> aiDefaults = aiDefaultsForPrefix(prefix);
    Map<String, String> overrides = resolveOverridesByPrefix(prefix);

    // 순서: 플랫폼 키(기존 화면 순서 유지) → AI 동작 키(기본값 맵 순서) → 그 밖의 오버라이드 전용 키.
    java.util.LinkedHashSet<String> allKeys = new java.util.LinkedHashSet<>(platformByKey.keySet());
    allKeys.addAll(aiDefaults.keySet());
    allKeys.addAll(overrides.keySet());

    return allKeys.stream()
        .map(
            key -> {
              SettingResponse platformRow = platformByKey.get(key);
              return new ResolvedSettingResponse(
                  key,
                  displayValue(key, overrides, platformRow, aiDefaults),
                  platformRow != null ? platformRow.description() : null,
                  platformRow != null ? platformRow.updatedAt() : null,
                  overrides.containsKey(key),
                  SettingsOverridePolicy.isTenantOverridable(key));
            })
        .collect(Collectors.toList());
  }

  /**
   * 화면에 보일 값: 오버라이드(여기서 마스킹) → 플랫폼 행(이미 마스킹됨) → 코드 기본값(플랫폼 행이
   * 없는 AI 동작 키).
   */
  private String displayValue(
      String key,
      Map<String, String> overrides,
      SettingResponse platformRow,
      Map<String, String> aiDefaults) {
    if (overrides.containsKey(key)) return maskIfSecret(key, overrides.get(key));
    if (platformRow != null) return platformRow.value();
    return aiDefaults.get(key);
  }

  /**
   * 프리픽스에 속한 AI 동작 키의 코드 기본값(순서 보존). {@code prefix} 규칙은
   * {@link SettingsRepository#findByPrefix} 와 같다({@code prefix + "."} 로 시작).
   */
  private static Map<String, String> aiDefaultsForPrefix(String prefix) {
    Map<String, String> out = new java.util.LinkedHashMap<>(AiBehaviorDefaults.all());
    out.keySet().removeIf(key -> !key.startsWith(prefix + "."));
    return out;
  }

  /**
   * 오버라이드 판정의 <b>프리픽스 형태</b>. {@link #getAsMap}·{@link #getResolvedByPrefix} 가
   * 공유한다. {@link TenantSettingsRepository#findByPrefix} 로 <b>한 번의 쿼리</b>에 후보를 전부
   * 가져온 뒤 화이트리스트로 걸러낸다 — 키마다 {@code findValue} 를 부르던 이전 구현은 프리픽스당
   * N+1 쿼리를 냈다.
   *
   * <p>컨텍스트가 없으면 즉시 빈 맵(쿼리 없음) — 배경 경로(JobRunr·{@code @Scheduled})는 이
   * 분기로 나간다. SMTP 는 이 경로에서 "미설정"이 된다(#712: 플랫폼 폴백 없음).
   *
   * <p>예전에는 여기서 SMTP 연결 5키를 원자적으로 채우는 번들 규칙이 돌았다. 그 규칙은 테넌트 값과
   * 플랫폼 값이 섞여 "테넌트 호스트 + 플랫폼 자격증명"이 되는 것을 막기 위한 것이었는데, #712 로
   * 플랫폼 평면이 사라져 섞일 상대가 없으므로 삭제했다.
   *
   * <p><b>{@link SettingsOverridePolicy.Plane#EXTERNAL_OWNER} 키도 여기서 뺀다(방어적 중복).</b>
   * {@code ai.credential} 은 {@code tenant_settings} 에 실재하지만 화이트리스트에 없어 이미
   * 걸러진다. 화이트리스트가 실수로 넓어져도 비밀 하위 필드 암호문이 두 화면 경로로 새지 않게
   * 평면 판정으로 한 번 더 뺀다.
   */
  private Map<String, String> resolveOverridesByPrefix(String prefix) {
    if (TenantContext.get() == null) return Map.of();
    Map<String, String> candidates = tenantSettingsRepository.findByPrefix(prefix);
    candidates.keySet().removeIf(
        key ->
            !SettingsOverridePolicy.isTenantOverridable(key)
                || planeOf(key) == SettingsOverridePolicy.Plane.EXTERNAL_OWNER);
    return candidates;
  }

  /**
   * <b>테넌트 평면</b> 쓰기. {@link SettingsOverridePolicy#isTenantOverridable} 화이트리스트
   * (12키: 테넌트 전용 {@code ai.*} 동작 키 6 + 테넌트 전용 {@code smtp.*} 6. AI 자격증명은
   * 이 화이트리스트가 아니라 {@link AiCredentialService} 로만 쓴다)만 받아
   * {@code tenant_settings} 에
   * 저장한다 — {@code system_settings}(전역 행)는 절대 건드리지 않는다. 이 구분이 이 밴드의
   * 존재 이유다(오늘의 결함: 한 테넌트의 저장이 전 테넌트에 적용됨).
   *
   * <p>{@link com.smartfirehub.settings.model.AiCredentialSlot#ownedKeys() 자격증명 슬롯 소유 키} 는 <b>이 메서드로 저장할 수 없다</b> — 화이트리스트에 없고,
   * {@link #rejectExternalOwnerKey} 가 별도로도 막는다. 유일한 쓰기 API 는
   * {@link AiCredentialService#save} 다.
   *
   * <p>거부 메시지에 키 이름을 넣는다 — web 이 어느 필드가 잠겼는지 사용자에게 보여줄 수 있어야
   * 하기 때문이다. 임베딩 설정은 {@code EmbeddingConfigService} 전용이다(#713).
   *
   * <p>{@link #validateValues} 는 그대로 지난다 — 범위 검증(예: max_turns 1~50)은 값이
   * {@code tenant_settings} 로 가든 {@code system_settings} 로 가든 똑같이 필요하다.
   *
   * <p><b>모든 키가 {@link #dropMaskSentinels}·{@link #validateSmtpPort}·{@link #validateSmtpHost}·
   * {@link #validateSmtpFromAddress}·{@link #encryptSecrets} 를
   * 지난다</b>(모두 키로 스스로 가드하므로 SMTP 부분맵을 떼어낼 필요가 없다). P7-c1 이전 이 자리에는
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
      // 화이트리스트보다 먼저 불러 "AiCredentialService 를 쓰라"는 정확한 안내로 거부한다.
      rejectExternalOwnerKey(key);
      if (!SettingsOverridePolicy.isTenantOverridable(key)) {
        throw new IllegalArgumentException("플랫폼 관리자만 변경할 수 있는 설정입니다: " + key);
      }
    }

    // 세 값 변환이 **맵 전체**를 지난다. 셋 다 키로 스스로 가드하므로(센티널은 SECRET_KEYS,
    // 포트 검증은 smtp.port 유무) SMTP 부분맵을 떼었다 다시 합칠 이유가 없다 — 그 분리·재병합은
    // 하지 않아도 되는 일을 열 줄로 하고 있었다. 프리픽스로 미리 갈라 놓으면 "이 변환 대상인가"의
    // 답이 키가 아니라 프리픽스에서 나오고, SMTP 아닌 비밀 키가 테넌트에 열리는 순간 평문으로
    // 저장된다(BYO 키 정책으로 비밀 키 하나를 화이트리스트에 여는 한 줄).
    Map<String, String> payload = dropMaskSentinels(settings);
    validateValues(payload);
    validateSmtpPort(payload);
    validateSmtpHost(payload);
    validateSmtpFromAddress(payload);
    Map<String, String> toWrite = encryptSecrets(payload);

    // 저장 대상은 **여기서만** 정한다 — 쓰기까지 공유 헬퍼에 넣으면 두 평면이 저장 대상에서
    // 갈라질 때 조용히 어긋난다(플랫폼은 system_settings, 테넌트는 tenant_settings).
    toWrite.forEach((key, value) -> tenantSettingsRepository.upsert(key, value, userId));
  }

  /**
   * 워크스페이스 SMTP 설정을 해제한다 — SMTP 6키({@link SettingsOverridePolicy#smtpKeys}) 행을 한
   * 번에 지운다(#712). 발신자 주소도 포함한다. 지운 뒤 워크스페이스는 미설정이 되어 메일 발송이
   * 명확한 오류로 실패한다(플랫폼 폴백 없음).
   *
   * <p><b>왜 키 하나씩이 아니라 묶음인가.</b> 플랫폼 기본값이 없어진 지금 키 하나를 지우는 것은
   * "기본값으로 돌아간다"가 아니라 "그 칸만 비운다"이고, 호스트만 남은 반쪽 설정이 조용히 생긴다.
   * 사용자 결정(2026-09-23)대로 해제는 6칸을 한 묶음으로 한다.
   *
   * <p><b>멱등</b> — 행이 하나도 없어도 성공이다. {@code @Transactional} 이 필수다 —
   * {@code tenant_settings} 는 RLS 테이블이라 GUC 가 트랜잭션이 열릴 때만 주입된다
   * ({@link #updateSettings} javadoc 과 같은 이유).
   */
  @Transactional
  public void clearSmtpSettings() {
    // 6키를 한 문장으로 지운다 — 키마다 DELETE 를 보내면 왕복만 늘고 원자성은 트랜잭션이 이미 준다.
    tenantSettingsRepository.deleteAll(SettingsOverridePolicy.smtpKeys());
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
   * <p>저장 계층의 fail-fast 자체는 옳다 — "행 삭제는 {@code delete} 로"라는 통로 분리를
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
   * 값이 <b>우리가 만든 마스크 그 자체</b>인지 판정한다 — "화면이 받은 마스크를 그대로 돌려보냈다
   * = 사용자가 안 고쳤다"의 근거이고, 참이면 그 키를 페이로드에서 통째로 드롭한다.
   *
   * <p><b>{@code startsWith("****")} 만으로는 안 된다.</b> 사용자가 비밀번호를
   * {@code ****Str0ngPass} 로 <b>새로 입력</b>하면 센티널로 오인해 키를 드롭하고, 예외 없이 204 가
   * 나가 화면이 "설정이 저장되었습니다" 토스트를 띄운다 — 저장된 것은 없고 메일은 옛 비밀번호로
   * 계속 나간다. 전형적인 "성공처럼 보이는 무동작"이다.
   *
   * <p>그래서 판정은 <b>정확 일치</b>다. 읽기 경로가 내보내는 마스크는 고정 표식
   * {@link #SECRET_MASK} 하나뿐이므로, 그 문자열 그 자체만 센티널이다. #725 이전에는 마스크가
   * {@code ****} + 평문 끝 4자(길이 8)이기도 해서 "길이 4 또는 8" 로 판정했고, 그 탓에
   * {@code ****abcd} 같은 <b>8자 비밀번호</b>가 조용히 버려졌다 — 마스크가 한 형태가 되면서 그
   * 구멍도 함께 닫혔다.
   *
   * <p>웹 화면은 이제 마스크를 되보내지 않는다(빈 칸 = 변경 없음으로 키를 뺀다). 그래도 이 판정을
   * 남기는 이유는 {@code GET} 응답을 그대로 {@code PUT} 하는 다른 클라이언트가 살아 있는 비밀번호를
   * 문자열 {@code ****} 로 덮어쓰지 못하게 하기 위해서다.
   *
   * <p><b>남는 잔여 위험</b>: 진짜 비밀번호가 정확히 {@code ****} 이면 조용히 드롭된다. 서버가
   * 내보낸 표식과 구분할 방법이 원리적으로 없다.
   */
  private static boolean isMaskSentinel(String value) {
    return SECRET_MASK.equals(value);
  }

  /**
   * 비밀 값({@link #SECRET_KEYS} — 현재 {@code smtp.password} 뿐, #713 이후 임베딩 키는 여기서 다루지
   * 않는다)은 저장 전 암호화한다. {@code smtp.password} 는 인증 없는 릴레이를 쓰는 경우 빈 문자열일
   * 수 있으므로, 빈 값은 암호화하지 않고 그대로 둔다(빈 ciphertext 복호화 실패 방지).
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
    // 빈 값은 암호화하지 않고 그대로 둔다(위 javadoc — 빈 ciphertext 복호화 실패 방지).
    return value.isBlank() ? value : encryptionService.encrypt(value);
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
   * 화면이 돌려보낸 <b>마스크 센티널</b>을 페이로드에서 떨어뜨린다 — 두 쓰기 평면이 공유한다.
   *
   * <p><b>판정 근거는 {@link #SECRET_KEYS} 다.</b> 예전에는 이 질문이 키 이름 나열로, 그것도
   * <b>두 곳에서 따로</b> 답해지고 있었다: 플랫폼 경로는 {@code ai.api_key}/{@code ai.cli_oauth_token}/
   * {@code embedding.api_key} 를 {@code boolean} 세 벌 + {@code filter} 세 벌로, SMTP 경로는
   * {@code normalizeSmtpPayload} 안에서 {@code "smtp.password".equals(key)} 로. 그래서
   * {@link #SECRET_KEYS} 에 다섯 번째 키를 추가하면 암호화·복호화·마스킹은 <b>자동으로</b> 맞는데
   * 센티널 드롭만 따라오지 않았다 — 화면이 돌려보낸 마스크 문자열이 살아 있는 비밀 위에
   * 암호화되어 저장된다. {@link #encryptIfSecret} javadoc 이 "이 밴드가 고친 결함이 정확히 그
   * 형태였다"고 적어 둔 그 모양이, 같은 파일 안에서 한 자리만 옮겨 살아남아 있었다.
   *
   * <p>비밀 키가 아니면 아무것도 하지 않으므로 <b>어떤 맵에 적용해도 무해</b>하다
   * ({@link #encryptSecrets} 와 같은 성질) — 그래서 호출부가 "이 그룹은 비밀이 들어올 수 있는
   * 그룹인가"를 프리픽스로 미리 판단할 필요가 없고, 그 판단이 바로 이 밴드가 고친 결함의 모양이다.
   *
   * <p><b>센티널은 현재 값과 비교하지 않고 그냥 떨어뜨린다.</b> {@link #isMaskSentinel} 이 참이면
   * "화면이 받은 마스크를 그대로 돌려보냈다 = 사용자가 안 고쳤다"는 뜻이라 현재 값이 무엇이든
   * 결론이 같다. 비교하려면 현재 값을 읽어야 하고, 그러려면 이 헬퍼가 <b>어느 저장소를 읽을지</b>
   * 알아야 한다 — 두 평면이 공유하는 자리에 평면 지식이 읽기 쪽 문으로 다시 들어온다.
   *
   * <p>입력 맵은 건드리지 않고 새 맵을 만든다({@code Map.of} 로 온 불변 맵이 흔하다).
   */
  private static Map<String, String> dropMaskSentinels(Map<String, String> settings) {
    Map<String, String> kept = new HashMap<>();
    settings.forEach(
        (key, value) -> {
          // 드롭하면 그 키는 저장 대상에서 통째로 빠진다 — 저장하면 살아 있는 비밀번호가 문자열
          // "****" 로 덮어써져 "아무것도 안 바꿨는데 메일이 안 나간다"가 된다.
          if (SECRET_KEYS.contains(key) && isMaskSentinel(value)) return;
          kept.put(key, value);
        });
    return kept;
  }

  /**
   * {@code smtp.port} 범위 검증(1~65535). #712 이후 SMTP 쓰기는 테넌트 경로({@link #updateSettings})
   * 하나뿐이다. 키가 없으면 아무것도 하지 않으므로 <b>어떤 맵에 적용해도 무해</b>하다.
   *
   * <p>검증이 플랫폼 경로에만 있던 동안 테넌트는 {@code 99999} 를 저장할 수 있었다 — 저장은
   * 성공하고 실패는 한참 뒤 메일 발송에서 드러난다. 빈 문자열도 여기서 거부되므로 저장된
   * {@code smtp.port} 는 항상 유효한 숫자다.
   */
  private static void validateSmtpPort(Map<String, String> settings) {
    if (!settings.containsKey("smtp.port")) return;
    String portStr = settings.get("smtp.port");
    // 표기 검사는 AI 정수 키와 같은 문법을 쓴다(#727) — 예전 Integer.parseInt 는 "+25" 를 받아
    // 그대로 저장했다.
    if (!INTEGER_SYNTAX.matcher(portStr).matches()) {
      throw new IllegalArgumentException("SMTP 포트 번호가 유효하지 않습니다: " + portStr);
    }
    try {
      int port = Integer.parseInt(portStr);
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("SMTP 포트 번호는 1에서 65535 사이여야 합니다. 입력값: " + port);
      }
    } catch (NumberFormatException e) {
      // 문법은 맞지만 int 범위를 넘는 자릿수(예: 99999999999).
      throw new IllegalArgumentException("SMTP 포트 번호가 유효하지 않습니다: " + portStr);
    }
  }

  /**
   * {@code smtp.host} 는 비어 있거나 공백을 담을 수 없다(#728). 키가 없으면 아무것도 하지 않는다
   * (부분 PUT 에서 "키 없음 = 손대지 않음").
   *
   * <p>공백만 있는 호스트({@code "  "})가 204 로 저장되던 동안, 소비자({@code EmailChannel}·
   * {@code EmailDeliveryChannel}·연결 테스트)는 {@code isBlank()} 로 그것을 "미설정"으로 읽었다 —
   * 저장은 성공했는데 화면은 미설정이라고 말하는 상태다. 빈 문자열도 같은 상태를 만들므로 함께
   * 막는다: 설정을 지우는 통로는 {@link #clearSmtpSettings} 하나다. 앞뒤·중간 공백이 섞인 호스트는
   * 그대로 저장되면 발송 시점의 DNS 실패로만 드러나므로 다듬지 않고 거부한다(web 이 다듬어 보낸다).
   */
  private static void validateSmtpHost(Map<String, String> settings) {
    if (!settings.containsKey("smtp.host")) return;
    String host = settings.get("smtp.host");
    if (host.isBlank()) {
      throw new IllegalArgumentException("SMTP 호스트는 비어 있을 수 없습니다");
    }
    if (host.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("SMTP 호스트에는 공백을 넣을 수 없습니다. 입력값: " + host);
    }
  }

  /**
   * 발신자 주소에서 <b>주소 부분</b>의 표기 — {@code 로컬@도메인}, 공백·꺾쇠·두 번째 {@code @} 없음.
   * web 의 {@code lib/smtp-address.ts} 와 <b>같은 문법</b>이어야 한다(#728).
   *
   * <p>도메인에 점을 요구하지 않는다 — 사내 릴레이에서는 {@code alerts@mailhost} 가 합법적인
   * 발신자이고, 발송 코드({@code InternetAddress})도 받는다.
   */
  private static final Pattern SMTP_ADDRESS_SYNTAX = Pattern.compile("[^\\s@<>]+@[^\\s@<>]+");

  /**
   * {@code smtp.from_address} 형식 검증(#728). 키가 없으면 아무것도 하지 않는다.
   *
   * <p>검증이 없던 동안 {@code not-an-email} 이 204 로 저장됐고, 연결 테스트는 접속만 확인하므로
   * 실패는 실제 메일이 나갈 때({@code MimeMessageHelper.setFrom}) 에야 드러났다.
   *
   * <p><b>받는 형태는 발송 코드가 받는 형태와 같다</b>: {@code 주소} 또는 {@code 표시명 <주소>} 하나.
   * {@code EmailDeliveryChannel} 은 저장된 문자열을 {@code helper.setFrom(String)} 에 그대로 넘기고,
   * 그 메서드는 {@link InternetAddress#parse} 로 <b>정확히 한 개</b>의 주소를 요구한다 — 그래서
   * 여기서도 같은 파서로 읽고(쉼표로 이은 두 주소·그룹 표기는 거부), 주소 부분에 web 과 같은
   * {@link #SMTP_ADDRESS_SYNTAX} 를 적용한다.
   *
   * <p><b>빈 문자열은 그대로 통과시킨다.</b> {@code EmailDeliveryChannel} 이 빈 발신자를 기본
   * 발신자로 바꿔 보내므로 동작하는 상태이고, 필수 여부는 화면(web)의 규칙이다. 공백만 있는 값과
   * 앞뒤 공백은 거부한다(web 이 다듬어 보낸다).
   *
   * <p>{@link AddressException} 원문({@code Missing final '@domain'} 등)은 사용자에게 내보내지 않는다 —
   * 이 메시지는 화면 토스트에 그대로 나간다.
   */
  private static void validateSmtpFromAddress(Map<String, String> settings) {
    if (!settings.containsKey("smtp.from_address")) return;
    String from = settings.get("smtp.from_address");
    if (from.isEmpty()) return;
    if (!isSingleMailAddress(from)) {
      throw new IllegalArgumentException(
          "발신자 주소 형식이 올바르지 않습니다(예: noreply@example.com). 입력값: " + from);
    }
  }

  /** {@code 주소} 또는 {@code 표시명 <주소>} 하나인가 — {@link #validateSmtpFromAddress} 의 판정. */
  private static boolean isSingleMailAddress(String value) {
    if (!value.equals(value.strip())) return false;
    try {
      InternetAddress[] parsed = InternetAddress.parse(value, true);
      if (parsed.length != 1 || parsed[0].isGroup()) return false;
      parsed[0].validate();
      String address = parsed[0].getAddress();
      return address != null && SMTP_ADDRESS_SYNTAX.matcher(address).matches();
    } catch (AddressException e) {
      return false;
    }
  }

  /**
   * 실제 메일 발송이 쓰는 SMTP 접속 정보. <b>현재 워크스페이스가 저장한 값만</b> 읽고 비밀값을
   * <b>복호화</b>해서 돌려준다(#712: 테넌트 전용).
   *
   * <p><b>미설정이면 빈 맵이다.</b> 워크스페이스가 SMTP 를 등록하지 않았거나 테넌트 컨텍스트가 없는
   * 호출이면 플랫폼 값으로 폴백하지 않는다 — 소비자({@code EmailChannel}·{@code EmailDeliveryChannel}·
   * 연결 테스트)가 {@code smtp.host} 공백을 보고 "워크스페이스 설정 › 이메일에서 등록하라"는 오류로
   * 멈춘다. 옛 플랫폼 행이 {@code system_settings} 에 남아 있어도 읽지 않는다(V128 이 지운다).
   *
   * <p><b>화면용 읽기와 발송용 읽기는 요구가 정반대다.</b> {@link #getResolvedByPrefix} 는 같은
   * 데이터를 <b>마스킹</b>해서 내보내고(응답에 평문도 암호문도 실리면 안 된다), 이 메서드는
   * <b>복호화</b>해서 내보낸다(SMTP 인증에 평문이 필요하다). 그래서 마스킹 없는 해석 경로인
   * {@link #getAsMap} 위에 복호화를 얹는다. 테넌트 {@code smtp.password} 는 저장 시 암호화되므로
   * 복호화를 빼면 SMTP 인증이 <b>암호문으로</b> 시도돼 발송이 조용히 실패한다.
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

  /**
   * 정수 설정 값의 표기 — ASCII 숫자만(선택적 {@code -}). web 의 {@code lib/settings-number.ts} 와
   * <b>같은 문법</b>이어야 한다(#727).
   *
   * <p>{@code Integer.parseInt} 만 쓰면 문법이 web 과 어긋난다: {@code "+5"} 와 비-ASCII 숫자는
   * 받아서 <b>그 원문 그대로</b> 저장하고, web 의 {@code Number()} 가 정수로 보는 {@code "1e1"}·
   * {@code "5.0"} 은 {@code NumberFormatException} 원문({@code For input string: "1e1"})으로
   * 거부한다. {@code -} 를 문법에 남겨 두는 이유는 음수를 "표기 오류"가 아니라 "범위 오류"로
   * 알리기 위해서다(범위 검사가 곧바로 거부한다).
   */
  private static final Pattern INTEGER_SYNTAX = Pattern.compile("-?[0-9]+");

  /**
   * 소수 설정 값의 표기 — {@code 0}, {@code 1.0}, {@code 0.75} 같은 평범한 십진 표기만.
   *
   * <p>{@code Double.parseDouble} 은 {@code "NaN"}·{@code "0.5d"}·{@code "0x1p-1"}·{@code "1e-1"}
   * 을 전부 받는다. 특히 {@code NaN} 은 {@code v < 0 || v > 1} 을 <b>둘 다 거짓으로</b> 통과해
   * 범위 검사가 있는데도 저장됐다(#727).
   */
  private static final Pattern DECIMAL_SYNTAX = Pattern.compile("[0-9]+(\\.[0-9]+)?");

  /**
   * 정수 설정 값을 읽는다. 표기가 틀리거나 {@code int} 를 넘치면 <b>필드 이름을 담은</b> 메시지로
   * 거부한다 — 이 메시지는 화면 토스트에 그대로 나간다.
   */
  private static int parseIntSetting(String label, String value) {
    if (value == null || !INTEGER_SYNTAX.matcher(value).matches()) {
      throw new IllegalArgumentException(label + "는 정수로 입력해야 합니다. 입력값: " + value);
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      // 문법은 맞지만 int 범위를 넘는 자릿수.
      throw new IllegalArgumentException(label + "가 너무 큽니다. 입력값: " + value);
    }
  }

  private void validateValues(Map<String, String> settings) {
    settings.forEach(
        (key, value) -> {
          switch (key) {
            case "ai.max_turns" -> {
              int v = parseIntSetting("최대 턴 수", value);
              if (v < 1 || v > 50) throw new IllegalArgumentException("최대 턴 수는 1에서 50 사이여야 합니다");
            }
            case "ai.temperature" -> {
              if (value == null || !DECIMAL_SYNTAX.matcher(value).matches()) {
                throw new IllegalArgumentException(
                    "Temperature는 0.0에서 1.0 사이의 숫자로 입력해야 합니다. 입력값: " + value);
              }
              double v = Double.parseDouble(value);
              // 문법이 이미 NaN·Infinity 표기를 막지만, 아주 긴 자릿수가 무한대로 읽히는 경우까지
              // 범위 검사 앞에서 닫아 둔다(NaN 은 아래 비교를 둘 다 통과한다).
              if (!Double.isFinite(v) || v < 0 || v > 1)
                throw new IllegalArgumentException("Temperature는 0.0에서 1.0 사이여야 합니다");
            }
            case "ai.max_tokens" -> {
              int v = parseIntSetting("최대 토큰 수", value);
              if (v < 1 || v > 65536)
                throw new IllegalArgumentException("최대 토큰 수는 1에서 65536 사이여야 합니다");
            }
            case "ai.session_max_tokens" -> {
              int v = parseIntSetting("세션 최대 토큰 수", value);
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
            case "ai.model" -> {
              // 모델 이름 자체는 자유 형식이다(목록 대조는 화면·자격증명 검증이 한다). 다만 빈 값은
              // 저장하지 않는다 — AI 동작 6키는 빈 값을 허용하지 않는다는 것이 web 의 기존 계약이고
              // (빈 칸은 페이로드에서 빼고 저장을 막는다), 빈 모델이 저장되면 채팅 요청이 모델 없이
              // 나간다. API 직접 호출로만 도달하던 구멍이다(#727).
              if (value == null || value.isBlank())
                throw new IllegalArgumentException("모델은 비어있을 수 없습니다");
            }
            default -> {
              /* 값 검증이 없는 키(SMTP 문자열 키 등) */
            }
          }
        });
  }

}

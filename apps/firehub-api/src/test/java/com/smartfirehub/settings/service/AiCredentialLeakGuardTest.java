package com.smartfirehub.settings.service;

import static com.smartfirehub.settings.service.AiCredentialService.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SettingsTestSupport;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code ai.credential} 이 범용 설정 경로로 유출·우회되지 않는지 고정한다(Task 3).
 *
 * <p>이 키는 {@link SettingsService#SECRET_KEYS} 의 원소가 아니다(비밀이 최상위 값이 아니라
 * 하위 필드에 있다) — 아무 조치도 하지 않으면 {@code getAll}/{@code getResolvedByPrefix}/
 * {@code getAsMap} 이 이 JSON 값을(비밀 하위 필드의 AES 암호문까지) 그대로 내보낸다. 그리고
 * 이 키는 타입형 전환(2026-09)으로 {@link SettingsOverridePolicy#tenantOverridableKeys} 의
 * 원소가 됐다 — 화이트리스트 검사만으로는 범용 쓰기({@code updateSettings})를 막지 못한다.
 *
 * <p><b>정리 방식은 {@code AiCredentialServiceTest} 와 같다</b>(플랫폼 행 캡처·원복, 테넌트 행
 * 삭제) — 공유 테스트 DB 에 이 클래스가 심은 {@code ai.credential} 이 남으면 기본 테넌트(1번)를
 * 쓰는 다른 테스트의 {@code resolve()} 가 영향을 받는다.
 */
class AiCredentialLeakGuardTest extends IntegrationTestBase {

  private static final Long USER = null;

  @Autowired private SettingsService settingsService;
  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;

  private String platformOriginal;

  @BeforeEach
  void seed() {
    platformOriginal = SettingsTestSupport.rawSystemSettingValue(dsl, KEY);
    aiCredentialService.save(
        new AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", "sk-live-secret")), USER, true);

    // 시드가 실제로 반영됐는지 먼저 확인한다 — 확인하지 않으면 "행이 애초에 없어서 유출도
    // 없다"는 이유로 아래 모든 유출 테스트가 공허하게 통과할 수 있다.
    String raw = SettingsTestSupport.rawSystemSettingValue(dsl, KEY);
    assertThat(raw).as("시드가 반영되지 않으면 아래 테스트 전부가 공허해진다").contains("agentType");
  }

  @AfterEach
  void cleanup() {
    // AiCredentialServiceTest 의 @AfterEach 와 같은 순서(테넌트 행 삭제 → 플랫폼 행 원복) —
    // IntegrationTestBase.clearTenantContext() 보다 먼저 실행돼야 TenantContext.require() 가 통과한다.
    tenantSettingsRepository.delete(KEY);
    SettingsTestSupport.restoreSystemSettingValue(dsl, KEY, platformOriginal);
  }

  @Test
  void getAll_은_ai_credential_을_내보내지_않는다() {
    assertThat(settingsService.getAll()).extracting(SettingResponse::key).doesNotContain(KEY);
  }

  @Test
  void prefix_조회는_ai_credential_을_내보내지_않는다() {
    assertThat(settingsService.getResolvedByPrefix("ai"))
        .extracting(ResolvedSettingResponse::key)
        .doesNotContain(KEY);
    assertThat(settingsService.getAsMap("ai")).doesNotContainKey(KEY);
  }

  /**
   * 플랫폼 시드만으로는 잡지 못하는 유출 경로: {@code ai.credential} 은 이제 테넌트 오버라이드
   * 허용 키라 테넌트 행으로도 실재할 수 있다. {@code resolveOverridesByPrefix} 가 이 키를 걸러
   * 내지 않으면, 플랫폼 스트림만 거른 구현도 이 테스트에서는 유출이 드러난다 —
   * {@code getResolvedByPrefix}/{@code getAsMap} 이 오버라이드 맵을 그대로 {@code putAll} 하기
   * 때문이다.
   */
  @Test
  void 테넌트_오버라이드로_저장해도_prefix_조회는_ai_credential_을_내보내지_않는다() {
    aiCredentialService.save(
        new AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", "sk-tenant-secret")), USER, false);
    String tenantRaw = tenantSettingsRepository.findValue(KEY).orElseThrow();
    assertThat(tenantRaw).as("테넌트 시드가 반영되지 않으면 아래 단언이 공허해진다").contains("agentType");

    assertThat(settingsService.getResolvedByPrefix("ai"))
        .extracting(ResolvedSettingResponse::key)
        .doesNotContain(KEY);
    assertThat(settingsService.getAsMap("ai")).doesNotContainKey(KEY);
  }

  @Test
  void 단건_조회는_거부된다() {
    assertThatThrownBy(() -> settingsService.getValue(KEY)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 범용_쓰기로는_테넌트_평면에_저장할_수_없다() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of(KEY, "{}"), USER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * {@code updatePlatformSettings} 는 이 키가 세 서브 화이트리스트({@code ALLOWED_AI_KEYS} 등)
   * 어디에도 없어 그 검사만으로 이미 거부된다 — {@code rejectBundleKey} 호출은 방어적 중복이다
   * (뮤테이션 검사 보고 참고). 그래도 두 쓰기 경로가 항상 같은 관문을 지나야 한다는 계약을
   * 실행 가능한 단언으로 고정해 둔다.
   */
  @Test
  void 범용_쓰기로는_플랫폼_평면에도_저장할_수_없다() {
    assertThatThrownBy(() -> settingsService.updatePlatformSettings(Map.of(KEY, "{}"), USER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 위 테스트들이 키 이름만 본다면 이 테스트는 값 전체를 본다 — 나중에 키 이름이 바뀌어도
   * 유출이 잡히게 하는 마지막 그물이다. {@code sk-live-secret} 은 저장 시점에 암호화되므로
   * {@code doesNotContain} 은 그 자체로는 공허하지만(평문이 어차피 안 실린다),
   * {@code agentType}/{@code ai.credential} 문자열은 JSON 구조·키 이름이 흘러나오는지를 잡는다.
   */
  @Test
  void 어떤_범용_경로에도_문서_구조가_섞이지_않는다() {
    String all =
        settingsService.getAll().toString()
            + settingsService.getResolvedByPrefix("ai")
            + settingsService.getAsMap("ai");
    assertThat(all).doesNotContain("sk-live-secret").doesNotContain(KEY).doesNotContain("agentType");
  }
}

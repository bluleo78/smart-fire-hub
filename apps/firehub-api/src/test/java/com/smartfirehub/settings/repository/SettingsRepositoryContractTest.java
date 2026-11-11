package com.smartfirehub.settings.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.user.repository.AccessContractBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * SettingsRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>system_settings 는 테넌트 개념이 없는 전역 테이블이라 모든 호출을 테넌트 컨텍스트 없이 한다(리뷰 포인트 1). 이 인터페이스에는 쓰기가 없어 행은
 * 픽스처(소유자)가 넣는다. 키는 실행마다 고유한 접두로 만들어 공유 DB 의 다른 행과 섞이지 않게 하고, 정렬 비교용 키는 소문자 알파벳만 다르게 한다(PG 로캘 정렬과
 * Tibero 이진 정렬이 '_'·'.' 에서 갈린다).
 */
public abstract class SettingsRepositoryContractTest extends AccessContractBase {

  protected abstract SettingsRepository repository();

  private static final LocalDateTime AT = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 123_456_000);

  @Test
  @DisplayName("findByPrefix 는 프리픽스.% 행만 키 오름차순으로, 값·설명·수정 시각을 그대로 돌려준다")
  void findByPrefix_ordered_fieldsRoundTrip() {
    fx.insertSystemSetting(prefix + ".b", "2", "둘", AT);
    fx.insertSystemSetting(prefix + ".a", "1", null, AT.plusDays(1));
    // 마침표 없는 같은 접두 키·프리픽스 자체는 매칭하지 않는다.
    fx.insertSystemSetting(prefix + "x.c", "3", null, AT);
    fx.insertSystemSetting(prefix, "0", null, AT);

    List<SettingResponse> rows = fx.noTenant(() -> repository().findByPrefix(prefix));

    assertThat(rows)
        .containsExactly(
            new SettingResponse(prefix + ".a", "1", null, AT.plusDays(1)),
            new SettingResponse(prefix + ".b", "2", "둘", AT));
    assertThat(fx.noTenant(() -> repository().findByPrefix(prefix + "."))).isEmpty();
  }

  @Test
  @DisplayName("findByPrefix 는 프리픽스의 LIKE 특수문자를 글자 그대로 본다")
  void findByPrefix_escapesLikeWildcards() {
    String wild = prefix + "_w";
    fx.insertSystemSetting(prefix + "Zw.k", "z", null, AT);
    fx.insertSystemSetting(wild + ".k", "w", null, AT);

    assertThat(fx.noTenant(() -> repository().findByPrefix(wild)))
        .extracting(SettingResponse::key)
        .containsExactly(wild + ".k");
    assertThat(fx.noTenant(() -> repository().findByPrefix(prefix + "%"))).isEmpty();
  }

  @Test
  @DisplayName("getValue 는 있는 키의 값, 없는 키는 빈 값 — 테넌트 컨텍스트 없이")
  void getValue_presentAndAbsent() {
    fx.insertSystemSetting(prefix + ".v", "값", null, AT);

    assertThat(fx.noTenant(() -> repository().getValue(prefix + ".v"))).contains("값");
    assertThat(fx.noTenant(() -> repository().getValue(prefix + ".none"))).isEmpty();
  }

  /**
   * 빈 문자열(리뷰 포인트 2, controller Ruling) — 변환하지 않고 DB 고유 의미를 계약으로 둔다. NULL 허용인 설명은 PG {@code ""},
   * Tibero null. value 는 두 DB 모두 NOT NULL 이라 PG 는 {@code ""} 를 그대로 돌려주고, Tibero 에는 그런 행을 만들 수조차
   * 없다(넣는 순간 NOT NULL 위반).
   */
  @Test
  @DisplayName("빈 설명은 PG 에서 빈 문자열, Tibero 에서 null — 빈 값은 PG 만 저장된다")
  void emptyStrings_perDb() {
    fx.insertSystemSetting(prefix + ".d", "1", "", AT);
    assertThat(fx.noTenant(() -> repository().findByPrefix(prefix)).get(0).description())
        .isEqualTo(emptyStringIsNull() ? null : "");

    if (emptyStringIsNull()) {
      assertThatThrownBy(() -> fx.insertSystemSetting(prefix + ".e", "", null, AT))
          .isInstanceOf(DataIntegrityViolationException.class);
      return;
    }
    fx.insertSystemSetting(prefix + ".e", "", null, AT);
    assertThat(fx.noTenant(() -> repository().getValue(prefix + ".e"))).contains("");
  }
}

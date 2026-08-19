package com.smartfirehub.support;

import org.jooq.DSLContext;

/**
 * {@code system_settings}(전역·비 RLS 테이블)를 건드리는 테스트용 헬퍼.
 *
 * <p><b>왜 필요한가.</b> P7-b 밴드는 이 읽기/원복 쌍을 세 테스트 클래스에 각자 손으로 복사했고,
 * 읽기는 바이트 단위로 같은데 <b>원복 쪽이 이미 두 갈래로 갈라져 있었다</b>: 어떤 곳은
 * {@code update ... set value = ?} 로 원래 값을 되돌리고, 어떤 곳은
 * {@code delete from system_settings where key = ?} 로 지운다. 두 semantics 는 전제가 다르다 —
 * 앞은 "행이 원래 있었다", 뒤는 "이 테스트가 행을 만들었다". 다음 사람은 먼저 연 파일의 것을
 * 복사하게 되고, 여기는 <b>공유 test DB</b> 라 커밋된 잔재가 무관한 테스트를 깨뜨리는 실패
 * 방식이 이 프로젝트에서 이미 여러 번 나왔다(이 밴드에서도 한 번 냈다).
 *
 * <p>그래서 두 원복을 <b>이름으로 구분</b>해 노출한다. 어느 쪽인지 호출부에서 보이게 하는 것이
 * 이 클래스의 핵심이다.
 *
 * <p>{@code TenantRlsTestSupport} 에 두지 않은 이유: 그 파일은 RLS 가 걸린 테넌트 테이블을 다루고,
 * {@code system_settings} 는 테넌트 구분이 없는 전역 테이블이다. 같은 파일에 두면 "이 헬퍼는
 * 테넌트 격리와 관련 있다"는 잘못된 신호를 준다. {@code IntegrationTestBase} 에 두지 않은 이유:
 * 이 헬퍼들은 {@code DSLContext} 를 받는 정적 함수이고, {@code SettingsOverridePolicyTest} 처럼
 * 베이스를 상속하지 않는 테스트도 쓸 수 있어야 한다.
 */
public final class SettingsTestSupport {

  private SettingsTestSupport() {}

  /** {@code system_settings.value} 원본(암호화된 그대로). 행이 없으면 {@code null}. */
  public static String rawSystemSettingValue(DSLContext dsl, String key) {
    var row = dsl.fetchOne("select value from system_settings where key = ?", key);
    return row == null ? null : row.get(0, String.class);
  }

  /**
   * <b>원래 있던 행</b>의 값을 되돌린다. 시드된 키를 테스트가 잠깐 바꿨을 때 쓴다.
   *
   * <p>{@code original} 이 {@code null} 이면 그 키는 애초에 행이 없었다는 뜻이므로
   * {@link #deleteSystemSetting} 으로 위임한다 — {@code update ... set value = null} 은
   * NOT NULL 제약에 걸린다.
   */
  public static void restoreSystemSettingValue(DSLContext dsl, String key, String original) {
    if (original == null) {
      deleteSystemSetting(dsl, key);
      return;
    }
    dsl.execute("update system_settings set value = ? where key = ?", original, key);
  }

  /** <b>테스트가 새로 만든 행</b>을 지운다. 시드된 키에는 쓰지 말 것 — 다른 테스트가 그 행을 읽는다. */
  public static void deleteSystemSetting(DSLContext dsl, String key) {
    dsl.execute("delete from system_settings where key = ?", key);
  }
}

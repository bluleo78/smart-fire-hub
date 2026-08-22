package com.smartfirehub.support;

import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;

import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.settings.service.SettingsService;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionTemplate;

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

  /**
   * {@code tenant_settings} 의 <b>저장된 원문</b>(암호화됐다면 암호문). 위 플랫폼 짝과 달리 RLS
   * 테이블이라 GUC 가 필요하므로 반드시 테넌트 트랜잭션 안에서 읽는다.
   *
   * <p><b>왜 여기로 올렸나.</b> P7-c1 의 두 테스트 클래스가 같은 일을 각자 private 헬퍼로 만들었고
   * <b>기전이 이미 갈라져 있었다</b> — 한쪽은 raw SQL, 다른 쪽은 {@code TenantSettingsRepository}
   * 를 지났다. 이 클래스 javadoc 이 예고한 분기가 그대로 재발한 것이다.
   *
   * <p><b>저장소가 아니라 raw SQL 을 쓴다.</b> 이 헬퍼가 답하는 질문은 "저장소가 무엇을 돌려주나"가
   * 아니라 "테이블에 무엇이 들어갔나"다(암호화·센티널 드롭의 전제 확인용). 저장소를 지나면 언젠가
   * 저장소가 값을 변형하는 날 그 변형이 <b>전제 단언에 그대로 반영되어</b> 아무것도 검증하지 못한다.
   *
   * <p><b>{@code Optional} 을 돌려주는 이유</b>(위 플랫폼 짝은 {@code String}/{@code null} 이다):
   * 호출부가 "행이 없다"와 "행은 있고 값이 빈 문자열"을 반드시 구별해야 하기 때문이다 — 번들 채움
   * 규칙의 전제 단언이 정확히 그 구별 위에 있다. {@code String}/{@code null} 로 두면
   * {@code assertThat(...).isEmpty()} 가 둘 중 어느 뜻인지 읽는 사람이 알 수 없다. 플랫폼 짝이
   * {@code String} 인 것은 {@link #restoreSystemSettingValue} 가 그 형태를 그대로 소비하기 때문이다.
   */
  public static Optional<String> rawTenantSettingValue(
      DSLContext dsl, TransactionTemplate transactionTemplate, Long tenantId, String key) {
    return runInTenantTransaction(
        transactionTemplate,
        tenantId,
        () -> {
          var row =
              dsl.fetchOne(
                  "select value from tenant_settings where tenant_id = ? and key = ?", tenantId, key);
          return row == null ? Optional.<String>empty() : Optional.ofNullable(row.get(0, String.class));
        });
  }

  /**
   * 화면이 실제로 받는 값 — {@code getResolvedByPrefix(prefix)} 결과에서 한 키를 꺼낸다.
   *
   * <p>같은 조회에서 한 키를 꺼내는 코드가 세 형태(헬퍼 1 + 인라인 2)로 흩어져 있었다. 한 파일
   * 안에서 두 형태가 공존하면 읽는 사람에게 "둘이 다른 것을 본다"는 잘못된 신호를 준다.
   */
  public static ResolvedSettingResponse resolvedSetting(
      SettingsService settingsService, String prefix, String key) {
    return settingsService.getResolvedByPrefix(prefix).stream()
        .filter(s -> key.equals(s.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(key + " 가 프리픽스 조회 결과에 없다: prefix=" + prefix));
  }
}

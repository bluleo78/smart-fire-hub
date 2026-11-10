package com.smartfirehub.global.platformdb.tibero.flyway;

import com.smartfirehub.global.platformdb.tibero.TiberoNameHash;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Tibero 플랫폼 DB 테넌트 격리(WD-492)의 Flyway 배선 — 소유자·런타임 두 계정 모델.
 *
 * <p>무엇을: 소유자 스키마 이름으로 인스턴스 전역 컨텍스트 이름을 계산하고, Flyway 설정에 자리표시자({@code tenantContext}·{@code
 * ownerSchema})와 런타임 계정 권한 부여 콜백({@link TiberoRuntimeGrantCallback})을 단다. 앱(Spring 커스터마이저)과 실서버
 * 테스트가 같은 메서드를 써서 두 곳의 설정이 어긋나지 않게 한다.
 *
 * <p>왜 컨텍스트 이름을 해시로 만드나: Tibero 컨텍스트 이름은 스키마가 아니라 인스턴스 전역이다. 같은 인스턴스의 다른 설치본·테스트 계정과 이름이 겹치면 남의
 * 컨텍스트를 덮어쓴다. 그래서 아웃박스 알림 이름(WD-493)과 같은 계산 — 스키마 SHA-256 앞 4바이트의 대문자 16진 — 으로 스키마마다 다른 이름을 쓴다.
 *
 * <p>단일 계정 모드: 런타임 계정이 없거나(null·빈 값) 소유자와 같으면(대소문자 무시) 콜백을 달지 않는다(자리표시자만 넣는다). 콜백이 하나라도 있으면 Flyway
 * 가 migrate 중에 콜백용 이벤트 연결을 하나 더 열기 때문에, 할 일이 없는 모드에서는 연결도 쓰지 않게 한다(실측: 크기 2 풀에서 연결 고갈). 기존 설치본은 두
 * 값이 같으므로 그대로 뜬다 — 다만 앱이 소유자로 붙어 정책에 걸리지 않는다(기동 WARN 은 Spring 커스터마이저가 남긴다).
 */
public final class TiberoTenantMigration {

  /** V156.1 이 쓰는 컨텍스트 이름 자리표시자. */
  public static final String TENANT_CONTEXT_PLACEHOLDER = "tenantContext";

  /** V156.1 이 쓰는 소유자 스키마(대문자) 자리표시자 — 정책 함수가 소유자 세션을 걸러내지 않으려고 비교한다. */
  public static final String OWNER_SCHEMA_PLACEHOLDER = "ownerSchema";

  /** 컨텍스트 이름 접두사. */
  static final String CONTEXT_PREFIX = "FH_TENANT_";

  /**
   * 계정 이름 규칙 — 따옴표 없는 Tibero 식별자. 이 이름이 SQL 문장(GRANT·자리표시자로 PL/SQL 리터럴)에 그대로 들어가므로 여기서 걸러 SQL 주입을
   * 막는다.
   */
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,127}");

  private TiberoTenantMigration() {}

  /**
   * 소유자 스키마 → 컨텍스트 이름. 결정적이라 같은 스키마의 모든 인스턴스·마이그레이션이 같은 이름을 쓴다. 대소문자는 Tibero 가 따옴표 없는 이름을 대문자로 접으므로
   * 대문자로 맞춘 뒤 계산한다.
   */
  public static String contextNameFor(String ownerSchema) {
    return contextNameOf(normalize("소유자 스키마", ownerSchema));
  }

  /** 이미 {@link #normalize} 한 소유자 이름 → 컨텍스트 이름(다시 검사하지 않는다). */
  private static String contextNameOf(String normalizedOwner) {
    return CONTEXT_PREFIX + TiberoNameHash.hex8(normalizedOwner);
  }

  /** 런타임 계정이 없거나(null·빈 값) 소유자와 같으면(대소문자 무시) 단일 계정 모드다 — 권한 부여도 격리도 없다. */
  public static boolean isSingleAccount(String ownerSchema, String runtimeUser) {
    return runtimeUser == null
        || runtimeUser.isBlank()
        || runtimeUser.equalsIgnoreCase(ownerSchema);
  }

  /**
   * Flyway 설정에 테넌트 격리 자리표시자와 런타임 권한 콜백을 더한다.
   *
   * <p>교체하지 않고 더한다 — Spring Boot 는 커스터마이저보다 먼저 설정 파일의 자리표시자와 콜백 빈을 넣어 두고, Flyway 의 {@code
   * placeholders(...)}·{@code callbacks(...)} 는 기존 값을 통째로 바꾸기 때문이다.
   *
   * @param ownerSchema 소유자 스키마(= Flyway 계정) 이름
   * @param runtimeUser 앱 풀 계정 이름, 없으면 null(단일 계정 모드 — 콜백을 달지 않는다)
   * @throws IllegalArgumentException 이름이 따옴표 없는 식별자 규칙에 맞지 않을 때
   */
  public static FluentConfiguration configure(
      FluentConfiguration c, String ownerSchema, String runtimeUser) {
    String owner = normalize("소유자 스키마", ownerSchema);
    String runtime = isSingleAccount(owner, runtimeUser) ? null : normalize("런타임 계정", runtimeUser);

    Map<String, String> placeholders = new HashMap<>(c.getPlaceholders());
    placeholders.put(TENANT_CONTEXT_PLACEHOLDER, contextNameOf(owner));
    placeholders.put(OWNER_SCHEMA_PLACEHOLDER, owner);

    c.placeholders(placeholders);
    if (runtime == null) {
      // 단일 계정 — 줄 권한이 없다. 콜백을 달면 Flyway 가 이벤트 연결을 하나 더 열 뿐이다.
      return c;
    }
    List<Callback> callbacks = new ArrayList<>(Arrays.asList(c.getCallbacks()));
    callbacks.add(new TiberoRuntimeGrantCallback(owner, runtime));
    return c.callbacks(callbacks.toArray(Callback[]::new));
  }

  /**
   * 이름이 규칙에 맞으면 대문자로 접어 돌려주고(Tibero 가 따옴표 없는 이름을 대문자로 접는다), 아니면 IllegalArgumentException. 값은 메시지에
   * 넣는다 — 계정 이름은 비밀이 아니다.
   */
  private static String normalize(String what, String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          what + " 이름은 따옴표 없는 식별자([A-Za-z][A-Za-z0-9_$#]{0,127})여야 합니다: " + name);
    }
    return name.toUpperCase(Locale.ROOT);
  }
}

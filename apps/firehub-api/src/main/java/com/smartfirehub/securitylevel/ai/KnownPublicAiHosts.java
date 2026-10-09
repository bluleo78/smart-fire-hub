package com.smartfirehub.securitylevel.ai;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 공용 호스팅이 <b>확실한</b> AI SaaS 호스트 목록(스펙 §7.6 — 자체 호스팅 오선언 방지).
 *
 * <p>자체 호스팅 선언은 관리자 판단이 기준이라 서버가 "공용처럼 보이는" 주소까지 막지는 않는다(사설 게이트웨이·사내 도메인을 오판해 정상 선언을 막게 된다). 대신 누구나
 * 아는 공용 SaaS 엔드포인트는 자체 호스팅일 수 없으므로 그 선언만 400 으로 거부한다 — 오선언이면 '자체 호스팅 모델만' 등급 데이터가 그대로 외부로 나가기 때문이다.
 * 가능성 판단(경고)은 웹 {@code isLikelyPublicEndpoint} 가, 확실한 거부는 이 목록이 맡는다.
 *
 * <p><b>웹과 같은 목록이다.</b> 웹 {@code apps/firehub-web/src/lib/hosting-location.ts} 의 {@code
 * KNOWN_PUBLIC_AI_HOSTS} 와 언어가 달라 한 곳에 정의할 수 없으므로, 양쪽
 * 테스트(KnownPublicAiHostsTest·hosting-location.test.ts) 가 같은 사례 표로 일치를 고정한다 — 한쪽만 고치면 그 표가 깨진다.
 *
 * <p>비교는 호스트 <b>정확 일치</b>다(소문자·끝 점 제거). 접미사 와일드카드는 쓰지 않는다 — 고객 소유 하위 도메인을 공용으로 오판하지 않게.
 */
public final class KnownPublicAiHosts {

  /** 자체 호스팅 선언 거부 문구(400). 채팅 자격증명·임베딩 설정이 같은 문구를 쓴다. */
  public static final String MSG_SELF_HOSTED_PUBLIC = "공용 AI 서비스 주소는 자체 호스팅으로 선언할 수 없습니다";

  /** 공용 AI SaaS API 호스트. 웹 KNOWN_PUBLIC_AI_HOSTS 와 같은 집합이어야 한다. */
  public static final Set<String> HOSTS =
      Set.of(
          "api.openai.com",
          "api.anthropic.com",
          "generativelanguage.googleapis.com",
          "api.mistral.ai",
          "api.cohere.com",
          "api.cohere.ai",
          "api.groq.com",
          "openrouter.ai",
          "api.together.xyz",
          "api.deepseek.com",
          "api.voyageai.com",
          "api.fireworks.ai",
          "api.perplexity.ai",
          "api.x.ai");

  private KnownPublicAiHosts() {}

  /** URL 의 호스트가 확실한 공용 AI SaaS 인가. 파싱 실패·호스트 없음은 false(형식 오류는 각 경로의 URL 가드가 따로 막는다). */
  public static boolean isKnownPublic(String url) {
    return host(url).map(HOSTS::contains).orElse(false);
  }

  /** 자체 호스팅 선언 대상 URL 이 확실한 공용 호스트면 400(IllegalArgumentException)을 던진다. */
  public static void requireNotKnownPublic(String url) {
    if (isKnownPublic(url)) {
      throw new IllegalArgumentException(MSG_SELF_HOSTED_PUBLIC);
    }
  }

  /** 소문자 호스트, 끝 점(FQDN 표기 "api.openai.com.") 제거 — 끝 점을 남기면 같은 호스트가 목록을 우회한다. */
  private static Optional<String> host(String url) {
    if (url == null || url.isBlank()) return Optional.empty();
    try {
      String h = URI.create(url.trim()).getHost();
      if (h == null) return Optional.empty();
      h = h.toLowerCase(Locale.ROOT);
      while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
      return Optional.of(h);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}

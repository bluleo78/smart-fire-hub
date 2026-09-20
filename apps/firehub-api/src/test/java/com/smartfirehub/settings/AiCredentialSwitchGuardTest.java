package com.smartfirehub.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.settings.model.AiCredential;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * {@link AiCredential} 을 분기하는 두 인증 상태 컨트롤러(테넌트 {@code AiController}, 플랫폼
 * {@code PlatformAiController})가 <b>진짜 exhaustive switch</b> 로 작성돼 있는지 소스 텍스트로
 * 고정한다(Ruling #53, Task 13 fix round 2 — fix round 3 에서 {@code default} 회피를 추가로
 * 막는다).
 *
 * <p><b>왜 컴파일이 이 규약을 지켜주지 못하는가.</b> 두 메서드 모두 {@code switch (cred) { case
 * AiCredential.X ... }} 로 짜여 있어 {@code default} 가 없으면 새 변형이 추가될 때 컴파일
 * 오류로 막힌다 — 그런데 이 컴파일 보장은 <b>switch 라는 문법을 계속 쓴다는 전제 위에서만</b>
 * 성립한다. 리뷰(자문)가 {@code switch} 를 {@code instanceof AiCredential.X} 사슬 + 마지막
 * {@code else} 로 바꿔치기해 봤더니 <b>컴파일도 되고 기존 4테스트도 전부 통과했다</b> — 새
 * 변형이 생기면 조용히 {@code else} 분기(Anthropic 전용 검증 엔드포인트)로 흘러들어, 다른
 * 유형의 자격증명을 엉뚱한 곳에 인증 시도하는 <b>오인증이자 오과금</b>이 된다. 런타임 테스트는
 * 아직 존재하지 않는 타입을 검사할 수 없으므로(추가되기 전에는 만들 대상이 없다), 이 규약은
 * 소스 텍스트를 직접 읽어야만 지킬 수 있다 — {@code DataSchemaResolutionTest} 가 "손 조립 SQL"
 * 을 같은 방식(생산 소스 정규식/문자열 검사)으로 잡는 것과 같은 종류의 가드다.
 *
 * <p><b>{@code default} 회피도 같은 종류의 구멍이다(fix round 3, 재리뷰 지적).</b> 진짜
 * {@code switch (cred)} 문법을 그대로 두고 실존하는 case 를 전부 남긴 채 {@code default ->
 * verifyApiKey()} 한 줄만 더해도, 이전 버전의 이 가드(문자열 {@code "switch (cred)"} 존재 +
 * {@code "instanceof AiCredential"} 부재 + 변형별 {@code case} 존재)는 전부 통과했다 —
 * {@code default} 부재를 검사하지 않았기 때문이다. instanceof 사슬과 증상이 완전히 같다(새
 * 변형이 조용히 Anthropic 전용 엔드포인트로 흘러든다) — 그래서 이제 {@code default} 분기의
 * <b>부재</b>도 명시적으로 단언한다. 정규식은 공백에 관대하다(문자열 {@code "switch (cred)"}
 * 는 {@code switch(cred)}(공백 없음)로 재포맷하면 컴파일도 되고 의미도 같은데 예전 문자열
 * 매칭은 이걸 놓쳤다 — 전부 {@code containsPattern}/{@code doesNotContainPattern} 로
 * 바꿨다).
 *
 * <p>스프링 컨텍스트가 필요 없는 순수 단위 테스트다 — 디스크의 두 파일과
 * {@link AiCredential#getPermittedSubclasses()}(리플렉션)만 본다.
 */
class AiCredentialSwitchGuardTest {

  private static final String PLATFORM_CONTROLLER =
      "src/main/java/com/smartfirehub/platform/controller/PlatformAiController.java";
  private static final String TENANT_CONTROLLER =
      "src/main/java/com/smartfirehub/ai/controller/AiController.java";

  @Test
  void 플랫폼_인증상태_컨트롤러는_AiCredential_모든_변형을_case_로_다룬다() {
    assertExhaustiveSwitch(PLATFORM_CONTROLLER);
  }

  @Test
  void 테넌트_인증상태_컨트롤러는_AiCredential_모든_변형을_case_로_다룬다() {
    assertExhaustiveSwitch(TENANT_CONTROLLER);
  }

  /**
   * 이 리포지토리에 {@link AiCredential} 을 분기하는 곳이 이 둘만이라는 것 자체는 이 테스트의
   * 책임이 아니다(그건 {@code find_referencing_symbols} 류 도구의 일이다) — 여기서는 <b>이미
   * 알고 있는 두 파일</b>이 진짜 switch 를 쓰는지만 고정한다.
   */
  private static void assertExhaustiveSwitch(String relativePath) {
    Path file = resolve(relativePath);
    String source = read(file);

    assertThat(source)
        .as(
            "%s 는 `switch (cred)`(공백 유무 무관) 로 AiCredential 을 분기해야 한다 — instanceof" +
                " 사슬(마지막 else 포함)로 바꾸면 컴파일은 통과하지만 새 변형이 조용히 한쪽 분기로" +
                " 흘러든다",
            relativePath)
        .containsPattern("switch\\s*\\(\\s*cred\\s*\\)")
        .doesNotContain("instanceof AiCredential");

    // fix round 3(재리뷰 지적) — 진짜 switch(cred) 문법을 쓰면서 case 를 전부 남긴 채
    // `default -> ...`(또는 옛 콜론 스타일 `default: ...`) 한 줄만 더해도 위 두 단언은
    // 통과한다. `default` 는 새 변형이 추가될 때 컴파일 오류로 안 막히는 탈출구라 instanceof
    // 사슬과 위험이 완전히 같다 — 그 분기 자체가 없어야 한다. `defaultValue = "..."`(Spring
    // `@RequestParam`) 같은 무관한 "default" 단어와 섞이지 않도록 뒤에 화살표/콜론이 바로
    // 오는 패턴만 잡는다.
    assertThat(source)
        .as(
            "%s 에 `default` 분기가 있다 — 실존하는 case 를 전부 남겨 컴파일·기존 테스트를 모두" +
                " 통과하면서, 새 변형만 조용히 이 분기(Anthropic 전용 검증 엔드포인트)로 흘려보내는" +
                " 탈출구다. sealed interface 의 컴파일 타임 exhaustiveness 보장은 `default` 가" +
                " 없을 때만 유효하다 — 이 분기를 지워야 새 변형 추가가 다시 컴파일 오류로 막힌다",
            relativePath)
        .doesNotContainPattern("default\\s*(->|:)");

    for (Class<?> variant : AiCredential.class.getPermittedSubclasses()) {
      assertThat(source)
          .as(
              "%s 에 `case AiCredential.%s` 가 없다 — AiCredential 에 변형이 늘 때마다 이 파일도" +
                  " 함께 늘어야 한다(그렇지 않으면 새 유형이 이 switch 밖에서 처리된다는 뜻이라," +
                  " 오인증/오과금 위험이 그대로다)",
              relativePath, variant.getSimpleName())
          .contains("case AiCredential." + variant.getSimpleName());
    }
  }

  /** 작업 디렉터리가 `apps/firehub-api`(Gradle 기본)와 저장소 루트(IDE 등) 둘 다일 수 있다 —
   * {@code DataSchemaResolutionTest.mainJavaRoot()} 와 같은 보정. */
  private static Path resolve(String relativePath) {
    Path fromWorkingDir = Paths.get(relativePath).toAbsolutePath().normalize();
    if (Files.exists(fromWorkingDir)) {
      return fromWorkingDir;
    }
    return Paths.get("apps/firehub-api", relativePath).toAbsolutePath().normalize();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("소스 읽기 실패: " + path, e);
    }
  }
}

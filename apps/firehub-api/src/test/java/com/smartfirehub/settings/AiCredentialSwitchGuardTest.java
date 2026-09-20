package com.smartfirehub.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.settings.model.AiCredential;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link AiCredential} 을 {@code switch} 로 분기하는 <b>모든</b> 생산 소스가 <b>진짜 exhaustive
 * switch</b> 로 작성돼 있는지 소스 텍스트로 고정한다(Ruling #53, Task 13 fix round 2/3 — 이슈
 * #695 에서 하드코딩 2파일 → 전수 스캔).
 *
 * <p><b>왜 컴파일이 이 규약을 지켜주지 못하는가.</b> {@code switch (cred) { case AiCredential.X
 * ... }} 는 {@code default} 가 없으면 새 변형이 추가될 때 컴파일 오류로 막힌다 — 그런데 이
 * 컴파일 보장은 <b>switch 라는 문법을 계속 쓴다는 전제 위에서만</b> 성립한다. 리뷰(자문)가
 * {@code switch} 를 {@code instanceof AiCredential.X} 사슬 + 마지막 {@code else} 로 바꿔치기해
 * 봤더니 <b>컴파일도 되고 기존 테스트도 전부 통과했다</b> — 새 변형이 생기면 조용히 {@code else}
 * 분기(Anthropic 전용 검증 엔드포인트)로 흘러들어, 다른 유형의 자격증명을 엉뚱한 곳에 인증
 * 시도하는 <b>오인증이자 오과금</b>이 된다. 런타임 테스트는 아직 존재하지 않는 타입을 검사할 수
 * 없으므로(추가되기 전에는 만들 대상이 없다), 이 규약은 소스 텍스트를 직접 읽어야만 지킬 수
 * 있다 — {@code DataSchemaResolutionTest} 가 "손 조립 SQL" 을 같은 방식으로 잡는 것과 같은
 * 종류의 가드다.
 *
 * <p><b>{@code default} 회피도 같은 종류의 구멍이다.</b> 진짜 {@code switch} 문법을 그대로 두고
 * 실존하는 case 를 전부 남긴 채 {@code default -> verifyApiKey()} 한 줄만 더해도, 예전 가드는
 * 전부 통과했다 — {@code default} 부재를 검사하지 않았기 때문이다. 증상은 instanceof 사슬과
 * 완전히 같다. 그래서 {@code default} 분기의 <b>부재</b>도 명시적으로 단언한다.
 *
 * <h2>왜 파일 목록을 하드코딩하지 않는가 (이슈 #695)</h2>
 *
 * <p>예전 버전은 감시 대상이 두 파일로 <b>하드코딩</b>돼 있었고, 같은 불변식이 필요한 파일은
 * 실제로 다섯 개였다 — 감시받지 않는 세 곳에 누가 {@code default ->} 를 넣어도 이 테스트는
 * 아무 말도 하지 않았다. 이제 {@code src/main/java} 전체를 훑어 {@code case AiCredential.} 를
 * 쓰는 파일을 <b>발견</b>해 검사한다. 새 소비처가 생겨도 자동으로 감시 대상이 된다.
 *
 * <p>덧붙여, #695 에서 값 조립형 분기(채팅·분류·프로액티브 세 곳)는 {@link AiCredential} 의
 * 유형별 메서드({@code applyTo}/{@code isComplete})로 내려가 {@code switch} 자체가 사라졌다 —
 * 그쪽은 이제 <b>컴파일</b>이 지킨다(변형 추가 시 "메서드 미구현" 오류). 텍스트 가드가 필요한
 * 것은 값이 아니라 <b>호출할 서비스 메서드</b>를 고르는 컨트롤러 계층 분기뿐이다.
 *
 * <p>스프링 컨텍스트가 필요 없는 순수 단위 테스트다 — 디스크의 소스와
 * {@link AiCredential#getPermittedSubclasses()}(리플렉션)만 본다.
 */
class AiCredentialSwitchGuardTest {

  /** 이 표식을 담은 생산 소스는 전부 exhaustive switch 규약의 대상이다. */
  private static final String SWITCH_MARKER = "case AiCredential.";

  /**
   * 정의 파일 자신은 두 스캔 모두에서 제외한다.
   *
   * <p>{@link AiCredential} 의 클래스 javadoc 은 이 가드가 어떻게 동작하는지를 설명하느라
   * {@code "case AiCredential."} 과 {@code "instanceof AiCredential"} 을 <b>문자열로 인용</b>한다.
   * 제외하지 않으면 정의 파일이 스스로 스캔 대상이 되어, 규약을 어긴 것이 아니라 규약을 설명한
   * 것 때문에 빨간불이 된다(실제로 한 번 그렇게 실패했다). 소비처가 아닌 파일이라 검사 대상도
   * 아니다.
   */
  private static final String DEFINITION_FILE = "AiCredential.java";

  /**
   * 스캔이 조용히 0건을 훑고 통과하는 사고(경로 오타, 디렉터리 이동)를 막는 하한선. 현재 대상은
   * 인증 상태 컨트롤러 둘({@code AiController}, {@code PlatformAiController})이다 — 줄어들 일이
   * 있다면 이 숫자와 함께 의도적으로 내려야 한다.
   */
  private static final int MIN_EXPECTED_FILES = 2;

  @Test
  void AiCredential_을_switch_로_분기하는_모든_생산소스가_exhaustive_하다() {
    List<Path> targets = findSwitchSites();

    assertThat(targets)
        .as(
            "`%s` 를 쓰는 생산 소스를 한 건도 찾지 못했다 — 스캔 경로가 깨졌거나 분기가 통째로"
                + " 사라졌다는 뜻이다. 둘 다 이 가드가 조용히 무력해진 상태라 실패로 다룬다",
            SWITCH_MARKER)
        .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_FILES);

    targets.forEach(AiCredentialSwitchGuardTest::assertExhaustiveSwitch);
  }

  /**
   * {@link AiCredential} 정의 파일 밖에서는 {@code instanceof AiCredential} 을 쓰지 않는다.
   *
   * <p><b>왜 이 단언이 따로 필요한가 (이슈 #695 정리 리뷰).</b> 위 스캔은 {@code case
   * AiCredential.} 라는 <b>표식으로 대상을 발견</b>한다. 그래서 누가 {@code switch} 를
   * {@code instanceof} 사슬로 바꾸면 그 파일은 표식을 잃고 <b>스캔에서 통째로 사라진다</b> —
   * 가드가 "위반을 못 잡는" 게 아니라 "대상이 아니게" 된다. 실제로 이 리팩터링의 첫 판이 세
   * 소비처의 switch 를 opencode 전용 {@code instanceof} 로 바꿨고, 그 순간 세 파일이 시야에서
   *빠졌다.
   *
   * <p>그래서 표식 기반 스캔과 <b>반대 방향</b>의 단언을 하나 더 둔다: 유형별로 갈리는 동작은
   * 전부 {@link AiCredential} 의 메서드여야 하고({@code applyTo}/{@code isComplete}/
   * {@code modelProblem}/{@code modelToSend}), 소비처는 타입을 물어보지 않는다. 정의 파일
   * 자신만 예외다(javadoc 과 구현이 그 이름을 쓴다).
   */
  @Test
  void 유형별_동작은_소비처의_instanceof_가_아니라_AiCredential_의_메서드여야_한다() {
    Path root = mainJavaRoot();
    try (Stream<Path> paths = Files.walk(root)) {
      List<String> offenders =
          paths
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".java"))
              .filter(p -> !p.getFileName().toString().equals(DEFINITION_FILE))
              .filter(p -> read(p).contains("instanceof AiCredential"))
              .map(p -> p.getFileName().toString())
              .sorted()
              .toList();

      assertThat(offenders)
          .as(
              "%s 가 AiCredential 을 instanceof 로 묻는다 — 유형별 동작은 AiCredential 의 메서드로"
                  + " 내려야 한다. instanceof 로 두면 (1) 새 변형이 조용히 그 분기를 건너뛰고,"
                  + " (2) 그 파일이 `case AiCredential.` 표식을 잃어 위 exhaustive 스캔의 대상에서도"
                  + " 빠진다 — 두 가드가 동시에 무력해진다",
              offenders)
          .isEmpty();
    } catch (IOException e) {
      throw new UncheckedIOException("생산 소스 스캔 실패: " + root, e);
    }
  }

  /** {@code src/main/java} 아래에서 {@link #SWITCH_MARKER} 를 담은 파일을 전부 찾는다. */
  private static List<Path> findSwitchSites() {
    Path root = mainJavaRoot();
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .filter(p -> !p.getFileName().toString().equals(DEFINITION_FILE))
          .filter(p -> read(p).contains(SWITCH_MARKER))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("생산 소스 스캔 실패: " + root, e);
    }
  }

  private static void assertExhaustiveSwitch(Path file) {
    String source = read(file);
    String name = file.getFileName().toString();

    assertThat(source)
        .as(
            "%s 는 switch 로 AiCredential 을 분기해야 한다 — instanceof 사슬(마지막 else 포함)로"
                + " 바꾸면 컴파일은 통과하지만 새 변형이 조용히 한쪽 분기로 흘러든다",
            name)
        .doesNotContain("instanceof AiCredential");

    // 진짜 switch 문법을 쓰면서 case 를 전부 남긴 채 `default -> ...`(또는 옛 콜론 스타일
    // `default: ...`) 한 줄만 더해도 위 단언은 통과한다. `default` 는 새 변형이 추가될 때
    // 컴파일 오류로 안 막히는 탈출구라 instanceof 사슬과 위험이 완전히 같다 — 그 분기 자체가
    // 없어야 한다. `defaultValue = "..."`(Spring `@RequestParam`) 같은 무관한 "default" 단어와
    // 섞이지 않도록 뒤에 화살표/콜론이 바로 오는 패턴만 잡는다.
    assertThat(source)
        .as(
            "%s 에 `default` 분기가 있다 — 실존하는 case 를 전부 남겨 컴파일·기존 테스트를 모두"
                + " 통과하면서, 새 변형만 조용히 그 분기로 흘려보내는 탈출구다. sealed interface 의"
                + " 컴파일 타임 exhaustiveness 보장은 `default` 가 없을 때만 유효하다",
            name)
        .doesNotContainPattern("default\\s*(->|:)");

    for (Class<?> variant : AiCredential.class.getPermittedSubclasses()) {
      assertThat(source)
          .as(
              "%s 에 `case AiCredential.%s` 가 없다 — AiCredential 에 변형이 늘 때마다 이 파일도"
                  + " 함께 늘어야 한다(그렇지 않으면 새 유형이 이 switch 밖에서 처리된다는 뜻이라,"
                  + " 오인증/오과금 위험이 그대로다)",
              name, variant.getSimpleName())
          .contains("case AiCredential." + variant.getSimpleName());
    }
  }

  /** 작업 디렉터리가 `apps/firehub-api`(Gradle 기본)와 저장소 루트(IDE 등) 둘 다일 수 있다 —
   * {@code DataSchemaResolutionTest.mainJavaRoot()} 와 같은 보정. */
  private static Path mainJavaRoot() {
    Path fromWorkingDir = Paths.get("src/main/java").toAbsolutePath().normalize();
    if (Files.isDirectory(fromWorkingDir)) {
      return fromWorkingDir;
    }
    return Paths.get("apps/firehub-api/src/main/java").toAbsolutePath().normalize();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("소스 읽기 실패: " + path, e);
    }
  }
}

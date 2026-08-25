package com.smartfirehub.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 권한 카탈로그(DB)와 실제 사용처(소스)를 대조한다.
 *
 * <p><b>왜 이 테스트가 있는가</b>: "코드가 요구하지 않는 권한이 롤에 부여된 채 남는" 결함이
 * <b>재발하는 부류</b>다. V116 이 {@code settings:write} 를, V117 이 {@code user:delete} 와
 * {@code user:read:self} 를 지웠다 — 셋 다 같은 모양이었고, 셋 다 사람이 우연히 발견했다.
 *
 * <p>위험은 "안 쓰는 행이 있다"가 아니라 <b>부여 행이 살아 있다는 것</b>이다. 전 테넌트의 ADMIN
 * 롤이 이미 그 권한을 갖고 있으므로, 누군가 나중에 그 코드로 {@code @RequirePermission} 을 한 줄
 * 붙이면 <b>아무도 권한을 부여하지 않았는데 즉시 열린다.</b> 권한 이름이 그 동작에 맞아 보일수록
 * 그 한 줄은 자연스러워 보인다.
 *
 * <p><b>왜 권한마다 트립와이어를 늘리지 않는가</b>: 그것은 이미 아는 인스턴스만 막는다. 이
 * 테스트는 <b>부류</b>를 막는다 — 새 권한을 시드하면서 쓰는 곳을 만들지 않으면 여기서 걸린다.
 */
class PermissionCatalogUsageTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /**
   * 소스에서 권한 코드처럼 보이는 문자열 리터럴. {@code @RequirePermission("x:y")} 뿐 아니라
   * {@code hasPermission("x:y")} 같은 다른 호출 형태도 함께 잡으려고 애너테이션이 아니라
   * <b>리터럴</b>을 센다 — 게이팅 방법이 늘어도 이 테스트가 따라갈 필요가 없다.
   */
  private static final Pattern PERMISSION_LITERAL =
      Pattern.compile("\"([a-z][a-z_]*(?::[a-z_]+)+)\"");

  /**
   * 코드 참조 없이 카탈로그에 남아 있어도 되는 권한. <b>비어 있는 것이 정상이다.</b>
   *
   * <p>여기에 무언가를 넣는 것은 "이 권한은 코드가 안 쓰는데 남긴다"는 선언이므로, 넣을 때는
   * 왜 남기는지와 언제 없앨 수 있는지를 함께 적어라. 근거 없이 추가하면 이 테스트는 다시
   * 사람의 주의에 의존하는 장식이 된다.
   */
  private static final Set<String> ALLOWED_UNREFERENCED = Set.of();

  @Test
  @DisplayName("카탈로그의 모든 권한은 소스에서 참조된다 — 고아 권한이 쌓이지 않는다")
  void everyPermissionIsReferencedInSource() throws IOException {
    Set<String> catalog =
        new TreeSet<>(dsl.fetch("select code from permission").getValues(0, String.class));
    // 카탈로그를 실제로 읽었다는 양성 대조군. 조회가 빈 집합을 주면(RLS·연결 문제 등) 아래
    // 단언은 "고아가 없다"가 아니라 "아무것도 검사하지 않았다"가 되어 조용히 통과한다.
    assertThat(catalog).hasSizeGreaterThan(30);

    Set<String> referenced = permissionLiteralsInMainSources();
    // 같은 이유의 양성 대조군 — 소스 스캔이 실제로 리터럴을 찾았는가.
    assertThat(referenced).contains("user:read", "ai:settings", "platform:tenant:create");

    Set<String> orphans = new TreeSet<>(catalog);
    orphans.removeAll(referenced);
    orphans.removeAll(ALLOWED_UNREFERENCED);

    assertThat(orphans)
        .withFailMessage(
            """
            소스에서 참조되지 않는 권한이 카탈로그에 있습니다: %s

            이 권한은 전 테넌트의 롤에 부여돼 있을 수 있는데 코드는 요구하지 않습니다. 누군가
            나중에 이 코드로 @RequirePermission 을 붙이면 아무도 권한을 부여하지 않았는데
            즉시 열립니다(V116/V117 이 지운 것과 같은 모양).

            둘 중 하나를 하세요:
              (1) 그 권한을 쓰는 곳을 만든다 — 게이팅하려던 라우트에 배선한다.
              (2) 새 마이그레이션으로 카탈로그에서 지운다(부여 행은 FK cascade 가 정리한다).
            정말 참조 없이 남겨야 한다면 ALLOWED_UNREFERENCED 에 근거와 함께 넣으세요.
            """,
            orphans)
        .isEmpty();
  }

  /** {@code apps/firehub-api/src/main/java} 전체를 훑어 권한 코드 리터럴을 모은다. */
  private Set<String> permissionLiteralsInMainSources() throws IOException {
    // 테스트 실행 디렉터리는 apps/firehub-api 다(Gradle 프로젝트 루트).
    Path mainJava = Path.of("src", "main", "java");
    assertThat(mainJava).as("소스 트리를 찾지 못하면 이 테스트는 공허해진다").isDirectory();

    Set<String> found = new TreeSet<>();
    try (Stream<Path> files = Files.walk(mainJava)) {
      List<Path> javaFiles =
          files.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
      for (Path file : javaFiles) {
        Matcher m = PERMISSION_LITERAL.matcher(Files.readString(file));
        while (m.find()) {
          found.add(m.group(1));
        }
      }
    }
    return found;
  }
}

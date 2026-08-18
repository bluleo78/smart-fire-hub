package com.smartfirehub.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@code schemaOwnerDataSource} 빈을 <b>누가 주입받을 수 있는지</b>를 소스 스캔으로 강제한다
 * (최종 전체 리뷰 B10, 룰링 — 방안 C 채택).
 *
 * <p><b>왜 이 가드가 필요한가.</b> 이 빈(자격증명 {@code app})은 dev·test·prod 세 환경 전부에서
 * {@code rolsuper=t, rolbypassrls=t} — 완전한 슈퍼유저다(런북 §3-2 실측). 그런데 이 빈은 평범한
 * {@code @Bean} 이라 스프링은 Java 접근 제한자로 주입 범위를 좁히지 않는다 — 아무 프로덕션
 * 클래스나 {@code @Autowired @Qualifier("schemaOwnerDataSource")} 를 선언하면 컴파일도 되고
 * 기존 테스트도 전부 통과한다(런북 §3-1 이 실측으로 확인한 그대로). 오늘은 우연히
 * {@link TenantSchemaProvisioner} 하나뿐이지만, 그것을 강제하는 것은 지금까지 아무것도 없었다.
 *
 * <p><b>왜 전용 비-슈퍼유저 롤(방안 A) 대신 이 가드(방안 C)만 채택했는가.</b> 컨트롤러 룰링:
 * 자격증명 경로를 바꾸는 방안 A 는 새 마이그레이션 + 기동 실패 위험이 있는 별도 승인 사안이라
 * 이연한다(런북 §3-4). 이 가드는 이 밴드가 이미 운영 중인 규약 가드 인프라
 * ({@code DataSchemaResolutionTest} 의 네 규칙과 같은 종류의 소스 스캔)와 같은 패턴이라
 * 사실상 무료이고, 최소한 "오늘 하나뿐이다"라는 사실을 다음 사람이 깨뜨리면 **빨갛게** 만든다
 * — 사전 차단은 아니지만(가드를 지우는 것 자체는 여전히 가능하다) 사후 감지는 확실히 한다.
 *
 * <p>스캔 대상은 {@code src/main/java} 뿐이다(다른 규약 가드와 같은 이유 — 테스트 코드는
 * {@code ownerDsl()} 헬퍼 패턴으로 이 빈을 합법적으로 여러 곳에서 참조한다, 예:
 * {@code TenantSchemaProvisionerTest}, {@code DataSchemaGrantIsolationTest},
 * {@code DataTableServiceTenantUniqueTest} 등 — 그건 이 가드의 대상이 아니다).
 */
class SchemaOwnerDataSourceExposureGuardTest {

  /** 이 문자열을 참조해도 되는 유일한 두 파일 — 빈 정의 지점과 유일한 프로덕션 주입 지점. */
  private static final List<String> ALLOWED_FILES =
      List.of(
          "com/smartfirehub/global/config/SchemaOwnerDataSourceConfig.java",
          "com/smartfirehub/global/tenant/TenantSchemaProvisioner.java");

  private static final String TARGET_TOKEN = "schemaOwnerDataSource";

  @Test
  void onlyAllowedFilesReferenceSchemaOwnerDataSource() {
    List<String> offenders =
        productionJavaFiles().stream()
            .filter(p -> read(p).contains(TARGET_TOKEN))
            .map(SchemaOwnerDataSourceExposureGuardTest::relativePath)
            .filter(relative -> ALLOWED_FILES.stream().noneMatch(relative::endsWith))
            .sorted()
            .toList();

    assertThat(offenders)
        .as(
            "schemaOwnerDataSource 는 app 자격증명(dev/test/prod 전부 슈퍼유저, 런북 §3-2)의"
                + " 풀이다 — TenantSchemaProvisioner 외의 프로덕션 클래스가 이걸 주입받으면"
                + " 안 된다(예외는 %s 뿐)",
            ALLOWED_FILES)
        .isEmpty();
  }

  /**
   * 허용 목록 자체가 낡지 않았는지 확인한다 — 두 파일이 실제로 이 토큰을 참조하는지, 그리고
   * 스캔이 0개를 훑고 조용히 통과하는 것이 아닌지(비공허성) 함께 본다.
   */
  @Test
  void allowedFilesActuallyReferenceTheToken_andScanIsNonEmpty() {
    List<Path> files = productionJavaFiles();
    assertThat(files).as("src/main/java 스캔이 비어 있다 — 스캔 루트가 깨졌을 수 있다").isNotEmpty();

    for (String allowed : ALLOWED_FILES) {
      List<Path> matched = files.stream().filter(p -> relativePath(p).endsWith(allowed)).toList();
      assertThat(matched).as("허용 목록의 파일 %s 를 스캔 결과에서 찾지 못했다 — 경로가 낡았다", allowed).hasSize(1);
      assertThat(read(matched.get(0)))
          .as("허용 목록의 파일 %s 가 실제로는 %s 를 참조하지 않는다 — 핀이 낡았다", allowed, TARGET_TOKEN)
          .contains(TARGET_TOKEN);
    }
  }

  private static final String MAIN_JAVA = "src/main/java";

  private static Path mainJavaRoot() {
    Path fromWorkingDir = Paths.get(MAIN_JAVA).toAbsolutePath().normalize();
    if (Files.isDirectory(fromWorkingDir)) {
      return fromWorkingDir;
    }
    Path fromRepoRoot = Paths.get("apps/firehub-api", MAIN_JAVA).toAbsolutePath().normalize();
    return Files.isDirectory(fromRepoRoot) ? fromRepoRoot : fromWorkingDir;
  }

  private static List<Path> productionJavaFiles() {
    Path root = mainJavaRoot();
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("프로덕션 소스 스캔 실패: " + root, e);
    }
  }

  private static String relativePath(Path path) {
    return mainJavaRoot().relativize(path).toString().replace('\\', '/');
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("소스 읽기 실패: " + path, e);
    }
  }
}

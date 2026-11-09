package com.smartfirehub.file.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 업로드 저장 파일명 확장자 검증 — HTTP 왕복 (WD-610).
 *
 * <p>결함: {@code POST /api/v1/files} 가 원본 파일명의 마지막 점 뒤를 검사 없이 저장 파일명에 붙여, {@code report.v1/final}·
 * {@code ../../etc/passwd}·300자 확장자 이름이면 저장 경로가 없는 하위 폴더를 가리키거나 이름이 너무 길어 500 이 났다. 이제 저장 파일명은 늘
 * {@code <uuid>[.<ext>]}(ext 는 {@code [a-z0-9]{1,10}}) 라 업로드가 200 이고, 파일은 업로드 폴더 안에 놓이며, 원본 이름은
 * originalName 으로 그대로 보존·다운로드 이름으로 쓰인다.
 *
 * <p>MockMvc + 실제 서비스로 하는 이유: 컨트롤러 → 서비스 → 디스크 → DB 저장까지 한 번에 지나야 「500 이 아니라 200」과 「업로드 폴더 안」을 함께
 * 확인할 수 있다. 업로드 루트는 테스트 컨텍스트 설정값을 그대로 읽는다(컨텍스트를 새로 띄우지 않으려고).
 */
@AutoConfigureMockMvc
class FileUploadStoredNameTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  @Value("${firehub.file.upload-dir:./uploads}")
  private String uploadDir;

  private SecurityFixture fx;
  private long user;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    user = fx.createUser("wd610-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, user, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() {
    // uploaded_files 는 RLS 대상이라 테넌트 트랜잭션 안에서 디스크 파일과 행을 함께 지운 뒤 사용자를 지운다(FK 순서).
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          List<String> paths =
              dsl.fetch("select storage_path from uploaded_files where uploaded_by = ?", user)
                  .getValues(0, String.class);
          for (String p : paths) {
            try {
              Files.deleteIfExists(Path.of(p));
            } catch (Exception ignored) {
              // 정리 실패는 테스트 판정과 무관하다.
            }
          }
          dsl.execute("delete from uploaded_files where uploaded_by = ?", user);
        });
    fx.deleteUser(user);
  }

  /** 예전에 500 이 나던 원본 이름들과 정상 대조군. */
  static List<String> originalNames() {
    return List.of(
        "report.v1/final",
        "../../etc/passwd",
        // 확장자 250자: 원본 이름(252자)은 DB 컬럼에 들어가지만 예전 저장 파일명(uuid 36 + 1 + 250)은 파일시스템 255바이트 제한을 넘었다.
        "x." + "e".repeat(250),
        "a.b\\c",
        "a.한글",
        "trailingdot.",
        "noext",
        ".hidden",
        "A.TXT",
        "a.b/c.txt",
        "ok.txt");
  }

  /** 어떤 원본 이름이든 200 이고, originalName 이 보존되며, 저장 파일은 업로드 폴더(chat-files) 안의 uuid[.ext] 다. */
  @ParameterizedTest
  @MethodSource("originalNames")
  void upload_anyOriginalName_returns200AndStoresInsideUploadDir(String originalName)
      throws Exception {
    MvcResult r = upload(originalName);

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    JsonNode first = objectMapper.readTree(body(r)).get(0);
    assertThat(first.get("originalName").asText()).isEqualTo(originalName);
    long id = first.get("id").asLong();

    Record2<String, String> row = storedRow(id);
    String storedName = row.value1();
    Path storagePath = Path.of(row.value2());
    Path chatFilesRoot = Paths.get(uploadDir, "chat-files").toAbsolutePath().normalize();

    assertThat(storedName).matches("[0-9a-f-]{36}(\\.[a-z0-9]{1,10})?");
    assertThat(storagePath.normalize().startsWith(chatFilesRoot))
        .as("저장 경로 %s 가 업로드 폴더 %s 안이어야 한다", storagePath, chatFilesRoot)
        .isTrue();
    assertThat(storagePath.getFileName().toString()).isEqualTo(storedName);
    assertThat(Files.readString(storagePath)).isEqualTo("hello");
  }

  /**
   * 원본 이름이 DB 보존 상한(255 코드포인트)을 넘으면(예: {@code x.} + e×300) 거부하지 않고 잘라서 보존한다 — 데이터 가져오기와 같은 정책. 예전에는
   * 저장 파일명 길이 초과로 500 이었다.
   */
  @Test
  void upload_originalNameOver255_returns200WithTruncatedName() throws Exception {
    String longName = "x." + "e".repeat(300);
    MvcResult r = upload(longName);

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    JsonNode first = objectMapper.readTree(body(r)).get(0);
    String returned = first.get("originalName").asText();
    assertThat(returned).isEqualTo(longName.substring(0, 255));
    Record2<String, String> row = storedRow(first.get("id").asLong());
    assertThat(row.value1()).matches("[0-9a-f-]{36}");
    assertThat(Path.of(row.value2()).normalize())
        .startsWith(Paths.get(uploadDir, "chat-files").toAbsolutePath().normalize());
  }

  /** 저장 확장자가 비어도 다운로드 이름(Content-Disposition)은 원본 이름 그대로다. */
  @Test
  void download_usesOriginalNameEvenWhenStoredExtensionDropped() throws Exception {
    String originalName = "report.v1/final";
    MvcResult up = upload(originalName);
    assertThat(up.getResponse().getStatus()).as(body(up)).isEqualTo(200);
    long id = objectMapper.readTree(body(up)).get(0).get("id").asLong();

    MvcResult down =
        mockMvc
            .perform(get("/api/v1/files/" + id + "/content").header("Authorization", token()))
            .andReturn();

    assertThat(down.getResponse().getStatus()).isEqualTo(200);
    assertThat(down.getResponse().getContentAsString()).isEqualTo("hello");
    ContentDisposition cd =
        ContentDisposition.parse(down.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
    assertThat(cd.getFilename()).isEqualTo(originalName);
  }

  private MvcResult upload(String originalName) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "files", originalName, "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
    return mockMvc
        .perform(multipart("/api/v1/files").file(file).header("Authorization", token()))
        .andReturn();
  }

  /** RLS 대상이라 테넌트 트랜잭션 안에서 저장 파일명·경로를 읽는다. */
  private Record2<String, String> storedRow(long id) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.select(
                    org.jooq.impl.DSL.field("stored_name", String.class),
                    org.jooq.impl.DSL.field("storage_path", String.class))
                .from("uploaded_files")
                .where("id = ?", id)
                .fetchOne());
  }

  private static String body(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(user, "u" + user, DEFAULT_TEST_TENANT_ID);
  }
}

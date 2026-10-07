package com.smartfirehub.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 최종 리뷰 C1: FILE 데이터셋 프리픽스는 서버 생성 전용이고 수정으로 바꿀 수 없다.
 *
 * <p>왜 필요한가: 오브젝트 목록·presigned URL 은 키가 데이터셋 프리픽스로 시작하는지만 본다. 사용자가 프리픽스를 고를 수 있으면 숨김(보안 등급) 데이터셋이나
 * 다른 테넌트 데이터셋의 {@code datasets/<id>/} 를 덮는 새 데이터셋을 만들어 그 파일을 열람할 수 있다. HTTP 경로 전체(요청 역직렬화 → 서비스 →
 * 오류 응답 코드)를 고정한다.
 */
@AutoConfigureMockMvc
class FileDatasetPrefixTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private long writer;
  private String marker;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    marker = "fdp" + System.nanoTime();
    writer = fx.createUser("fdp_w");
    users.add(writer);
    fx.removeUserRole(writer);
    // 새 데이터셋(기본 등급)을 볼 수 있도록 기본 등급보다 높은 자격 + 읽기·쓰기 권한.
    long roleId =
        fx.createRole("fdp_r_" + marker, fx.levelId("민감"), "dataset:read", "dataset:write");
    roles.add(roleId);
    fx.assignRole(writer, roleId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // file_dataset_config 는 dataset 삭제에 연쇄 삭제된다(V70 ON DELETE CASCADE).
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(writer, "fdp" + writer, DEFAULT_TEST_TENANT_ID);
  }

  private String createBody(String name, String prefixJson) {
    return "{\"name\":\""
        + name
        + "\",\"tableName\":\"file_"
        + System.nanoTime()
        + "\",\"storageType\":\"FILE\",\"originType\":\"SOURCE\",\"columns\":[]"
        + (prefixJson == null ? "" : ",\"prefix\":" + prefixJson)
        + "}";
  }

  private String storedPrefix(long datasetId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchValue("select prefix from file_dataset_config where dataset_id = ?", datasetId)
                .toString());
  }

  /** 숨김 데이터셋 프리픽스를 덮으려는 생성 — 400 + 고정 코드, 데이터셋이 만들어지지 않는다. */
  @Test
  void create_withClientPrefix_isRejectedWithCode() throws Exception {
    String name = marker + "_alias";
    mockMvc
        .perform(
            post("/api/v1/datasets")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name, "\"datasets/1/\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("FILE_PREFIX_NOT_ALLOWED"));

    Integer count =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> dsl.fetchCount(dsl.selectFrom("dataset").where("name = ?", name)));
    assertThat(count).isZero();
  }

  /** 프리픽스 없이(또는 빈 문자열로) 만들면 서버가 datasets/<id>/ 를 쓰고, 수정 요청의 prefix 는 무시된다(불변). */
  @Test
  void create_withoutPrefix_usesServerPrefix_andUpdateCannotChangeIt() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/datasets")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(marker + "_ok", "\"\"")))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = om.readTree(body).get("id").asLong();
    datasets.add(id);
    assertThat(storedPrefix(id)).isEqualTo("datasets/" + id + "/");

    // 수정 DTO 에는 prefix 가 없다 — 본문에 실어 보내도 저장 경로가 바뀌지 않아야 한다.
    mockMvc
        .perform(
            put("/api/v1/datasets/" + id)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + marker + "_ok2\",\"prefix\":\"datasets/1/\"}"))
        .andExpect(status().is2xxSuccessful());
    assertThat(storedPrefix(id)).isEqualTo("datasets/" + id + "/");
  }
}

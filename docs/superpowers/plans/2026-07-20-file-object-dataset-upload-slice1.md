# FILE 오브젝트 데이터셋 — 업로드 경로(presigned PUT) Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FILE 데이터셋에 presigned PUT URL 배치 발급 API와 웹 드래그앤드롭 업로드를 추가하여, 클라이언트가 앱을 통해(바이트는 MinIO로 직접) 파일을 업로드하는 경로를 완성한다.

**Architecture:** 앱은 오브젝트 키를 생성(프리픽스 격리·규약 강제)하고 해당 키에 대한 단기 presigned PUT URL을 서명해 반환한다. 클라이언트(로봇/브라우저)는 그 URL로 MinIO에 바이트를 직접 PUT하며 앱은 바이트를 프록시하지 않는다. 목록/서빙 경로(Slice 0)는 그대로 재사용한다.

**Tech Stack:** Spring Boot(Java) + jOOQ, `io.minio:minio:8.5.17`(presigned PUT), MinIO(S3 호환), React 19 + TS + TanStack Query + Axios, Playwright E2E.

## Global Constraints

- **한국어 주석 필수**: 클래스·메서드·주요 로직 블록에 무엇을/왜 설명.
- **키 규약(앱이 강제)**: `<prefix><robotId>/<yyyy-MM-dd>/<uuid>.<ext>`. `prefix`는 항상 trailing `/`로 끝난다(Slice 0의 `FileDatasetConfig`에서 보장).
- **입력 정제**: `robotId` → 소문자화 후 `[a-z0-9-]` 외는 `-`로 치환하고 앞뒤 `-` 제거, 비면 `web`. `ext` → 소문자화 후 `[a-z0-9]`만 남기고 최대 10자, 비면 `bin`.
- **배치 상한**: `files`는 1개 이상 1000개 이하(초과/0개는 400).
- **권한**: 업로드 발급 엔드포인트는 `@RequirePermission("dataset:write")`.
- **만료 분리**: 업로드 presign 기본 만료는 `firehub.minio.upload-presign-expiry-seconds`(기본 900초), GET 썸네일용(`presign-expiry-seconds`, 300초)과 분리.
- **YAGNI**: 자동 자격증명(madmin) 발급, 멀티파트 세분 제어, 파일별 DB 메타/검증은 이 slice 범위 밖.
- **테스트 필수**: backend는 TC, frontend는 Playwright E2E.
- **앱 미프록시**: 프론트의 presigned PUT은 앱 baseURL/인터셉터를 경유하지 않는 순수 axios로 수행.

## File Structure

**Backend (`apps/firehub-api`)**
- Modify `src/main/java/com/smartfirehub/file/config/MinioProperties.java` — `uploadPresignExpirySeconds` 필드 추가.
- Modify `src/main/resources/application.yml` — `upload-presign-expiry-seconds` 설정 추가.
- Modify `src/main/java/com/smartfirehub/file/service/FileObjectStorageService.java` — `presignedPutUrl`, `defaultUploadPresignExpiry` 추가.
- Create `src/main/java/com/smartfirehub/file/service/ObjectKeyGenerator.java` — 키 생성/정제(순수 컴포넌트).
- Create `src/main/java/com/smartfirehub/file/dto/UploadUrlRequest.java`, `UploadTarget.java`, `UploadUrlResponse.java`.
- Modify `src/main/java/com/smartfirehub/file/controller/FileObjectController.java` — `POST /upload-urls` 추가.
- Modify tests: `FileObjectStorageServiceTest.java`(생성자 6-arg + PUT 테스트), `FileObjectControllerTest.java`(setup 생성자 + 업로드 테스트).
- Create test `src/test/java/com/smartfirehub/file/service/ObjectKeyGeneratorTest.java`.

**Frontend (`apps/firehub-web`)**
- Modify `src/api/objects.ts` — 업로드 타입/`requestUploadUrls`/`putToPresignedUrl`/`extOf`.
- Modify `src/hooks/queries/useObjects.ts` — `useUploadObjects` 뮤테이션.
- Modify `src/pages/data/tabs/DatasetObjectsTab.tsx` — 드래그앤드롭 업로드 UI.
- Modify `e2e/flows/file-dataset-browser.spec.ts` — 업로드 E2E.

**Infra/Docs**
- Modify `docker-compose.yml`(dev), `docker-compose.prod.yml`(prod) — MinIO CORS 허용.
- Modify `docs/superpowers/specs/2026-07-20-file-object-dataset-poc-design.md` §10 — PUT CORS 반영.

---

### Task 1: 서비스 presigned PUT + 업로드 만료 설정

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/file/config/MinioProperties.java`
- Modify: `apps/firehub-api/src/main/resources/application.yml:54`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/file/service/FileObjectStorageService.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/file/service/FileObjectStorageServiceTest.java`

**Interfaces:**
- Produces: `FileObjectStorageService.presignedPutUrl(String bucket, String objectKey, int expirySeconds) -> PresignedUrlResponse`, `FileObjectStorageService.defaultUploadPresignExpiry() -> int`, `MinioProperties`가 6개 컴포넌트(`endpoint, accessKey, secretKey, bucket, presignExpirySeconds, uploadPresignExpirySeconds`).

- [ ] **Step 1: 기존 서비스 테스트에 PUT/만료 테스트 추가 (실패 확인용)**

`FileObjectStorageServiceTest.java` 상단 import에 추가:
```java
import static org.mockito.Mockito.verify;
import io.minio.http.Method;
import org.mockito.ArgumentCaptor;
```
`service()` 헬퍼의 `MinioProperties` 생성을 6-arg로 교체:
```java
    MinioProperties props =
        new MinioProperties("http://localhost:9000", "k", "s", "firehub-files", 300, 900);
```
테스트 메서드 2개 추가:
```java
  /** presignedPutUrl은 반드시 PUT 메서드로 서명하고, 발급된 URL을 그대로 반환해야 한다. */
  @Test
  void presignedPutUrl_usesPutMethodAndReturnsUrl() throws Exception {
    ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
        ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://localhost:9000/firehub-files/x/1.jpg?sig=put");

    PresignedUrlResponse resp = service().presignedPutUrl("firehub-files", "x/1.jpg", 900);

    verify(minioClient).getPresignedObjectUrl(captor.capture());
    assertThat(captor.getValue().method()).isEqualTo(Method.PUT);
    assertThat(resp.url()).contains("1.jpg");
    assertThat(resp.expiresInSeconds()).isEqualTo(900);
  }

  /** 업로드 만료는 GET(300)과 분리된 설정값(900)을 반환해야 한다. */
  @Test
  void defaultUploadPresignExpiry_returnsConfigured() {
    assertThat(service().defaultUploadPresignExpiry()).isEqualTo(900);
  }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `cd apps/firehub-api && ./gradlew compileTestJava`
Expected: FAIL — `MinioProperties` 생성자 인자 수 불일치 및 `presignedPutUrl`/`defaultUploadPresignExpiry` 미존재.

- [ ] **Step 3: `MinioProperties`에 필드 추가**

```java
/** MinIO(S3 호환) 접속·기본 버킷 설정을 담는 바인딩 객체. */
@ConfigurationProperties(prefix = "firehub.minio")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket,
    int presignExpirySeconds,
    // 업로드용 presign 기본 만료(초). 업로드는 GET 썸네일보다 느릴 수 있어 별도 설정으로 둔다.
    int uploadPresignExpirySeconds) {}
```

- [ ] **Step 4: `application.yml`에 설정 추가**

`presign-expiry-seconds` 라인 바로 아래에 추가:
```yaml
    presign-expiry-seconds: ${MINIO_PRESIGN_EXPIRY:300}
    upload-presign-expiry-seconds: ${MINIO_UPLOAD_PRESIGN_EXPIRY:900}
```

- [ ] **Step 5: 서비스에 PUT presign + 만료 게터 추가**

`FileObjectStorageService`의 `presignedGetUrl` 메서드 아래에 추가:
```java
  /** 업로드용 presigned URL 기본 만료(초). 업로드는 GET 썸네일보다 느릴 수 있어 별도 설정을 사용한다. */
  public int defaultUploadPresignExpiry() {
    return props.uploadPresignExpirySeconds();
  }

  /** 오브젝트 단건에 대한 단기 presigned PUT URL을 발급한다(클라이언트가 MinIO로 직접 업로드, 앱 미경유). */
  public PresignedUrlResponse presignedPutUrl(String bucket, String objectKey, int expirySeconds) {
    try {
      String url =
          minioClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.PUT)
                  .bucket(bucket)
                  .object(objectKey)
                  .expiry(expirySeconds)
                  .build());
      return new PresignedUrlResponse(url, expirySeconds);
    } catch (Exception e) {
      throw new RuntimeException("presigned PUT URL 발급 실패: " + e.getMessage(), e);
    }
  }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.service.FileObjectStorageServiceTest"`
Expected: PASS (기존 3개 + 신규 2개 = 5개).

> 참고: `GetPresignedObjectUrlArgs.method()` 게터가 존재하지 않으면(SDK 버전차) captor 대신 `assertThat(resp.url()).contains("1.jpg")`만 유지하고 PUT 검증은 컨트롤러/통합 레벨로 위임한다. 8.5.17에서는 `method()` 게터가 존재한다.

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/file/config/MinioProperties.java \
        apps/firehub-api/src/main/resources/application.yml \
        apps/firehub-api/src/main/java/com/smartfirehub/file/service/FileObjectStorageService.java \
        apps/firehub-api/src/test/java/com/smartfirehub/file/service/FileObjectStorageServiceTest.java
git commit -m "feat(file): presigned PUT URL 발급 + 업로드 만료 설정 분리"
```

---

### Task 2: 키 생성기 + 업로드 URL 발급 엔드포인트

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/service/ObjectKeyGenerator.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/UploadUrlRequest.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/UploadTarget.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/UploadUrlResponse.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/file/controller/FileObjectController.java`
- Create Test: `apps/firehub-api/src/test/java/com/smartfirehub/file/service/ObjectKeyGeneratorTest.java`
- Modify Test: `apps/firehub-api/src/test/java/com/smartfirehub/file/controller/FileObjectControllerTest.java`

**Interfaces:**
- Consumes: `FileObjectStorageService.presignedPutUrl(...)`, `defaultUploadPresignExpiry()` (Task 1). `FileDatasetConfig(datasetId, bucket, prefix)` (기존).
- Produces: `ObjectKeyGenerator.sanitizeRobotId/sanitizeExt/generateKey`; `POST /api/v1/datasets/{datasetId}/objects/upload-urls` → `UploadUrlResponse(List<UploadTarget> targets, int expiresInSeconds)`; DTOs `UploadUrlRequest(String robotId, List<FileSpec> files)` with nested `FileSpec(String ext)`, `UploadTarget(String key, String uploadUrl)`.

- [ ] **Step 1: 키 생성기 단위 테스트 작성 (실패 확인용)**

Create `ObjectKeyGeneratorTest.java`:
```java
package com.smartfirehub.file.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 오브젝트 키 생성/정제 규칙 검증(순수 단위 테스트). */
class ObjectKeyGeneratorTest {

  final ObjectKeyGenerator gen = new ObjectKeyGenerator();

  @Test
  void sanitizeRobotId_lowercasesReplacesInvalidAndDefaultsToWeb() {
    assertThat(gen.sanitizeRobotId("Robot 01!")).isEqualTo("robot-01");
    assertThat(gen.sanitizeRobotId(null)).isEqualTo("web");
    assertThat(gen.sanitizeRobotId("   ")).isEqualTo("web");
  }

  @Test
  void sanitizeExt_stripsNonAlnumCapsAndDefaultsToBin() {
    assertThat(gen.sanitizeExt("JPG")).isEqualTo("jpg");
    assertThat(gen.sanitizeExt(".Png")).isEqualTo("png");
    assertThat(gen.sanitizeExt(null)).isEqualTo("bin");
    assertThat(gen.sanitizeExt("verylongextension")).hasSize(10);
  }

  @Test
  void generateKey_followsConventionUnderPrefix() {
    String key = gen.generateKey("equip/", "robot-01", "jpg");
    // <prefix><robotId>/<yyyy-MM-dd>/<uuid>.<ext>
    assertThat(key).matches("equip/robot-01/\\d{4}-\\d{2}-\\d{2}/[0-9a-f-]{36}\\.jpg");
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.service.ObjectKeyGeneratorTest"`
Expected: FAIL — `ObjectKeyGenerator` 미존재(컴파일 에러).

- [ ] **Step 3: `ObjectKeyGenerator` 구현**

```java
package com.smartfirehub.file.service;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * FILE 데이터셋 업로드용 오브젝트 키 생성기.
 * 키 규약 "<prefix><robotId>/<yyyy-MM-dd>/<uuid>.<ext>"를 앱이 강제하여 프리픽스 격리와 명명 규칙을 보장한다.
 * 클라이언트는 확장자/로봇ID만 제안하며, 최종 키는 앱이 결정하므로 프리픽스 밖으로 나갈 수 없다.
 */
@Component
public class ObjectKeyGenerator {

  /** robotId 정제: 소문자화 후 [a-z0-9-] 외 문자는 '-'로 치환하고 앞뒤 '-' 제거. 결과가 비면 "web". */
  public String sanitizeRobotId(String robotId) {
    if (robotId == null) return "web";
    String s = robotId.toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("^-+|-+$", "");
    return s.isEmpty() ? "web" : s;
  }

  /** ext 정제: 소문자화 후 [a-z0-9]만 남기고 최대 10자로 자른다. 결과가 비면 "bin". */
  public String sanitizeExt(String ext) {
    if (ext == null) return "bin";
    String s = ext.toLowerCase().replaceAll("[^a-z0-9]", "");
    if (s.length() > 10) s = s.substring(0, 10);
    return s.isEmpty() ? "bin" : s;
  }

  /** 규약 키 생성. prefix는 항상 trailing '/'로 끝난다고 가정한다(FileDatasetConfig에서 보장). */
  public String generateKey(String prefix, String robotId, String ext) {
    return prefix
        + sanitizeRobotId(robotId)
        + "/"
        + LocalDate.now()
        + "/"
        + UUID.randomUUID()
        + "."
        + sanitizeExt(ext);
  }
}
```

- [ ] **Step 4: 키 생성기 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.service.ObjectKeyGeneratorTest"`
Expected: PASS (3개).

- [ ] **Step 5: DTO 3종 생성**

`UploadUrlRequest.java`:
```java
package com.smartfirehub.file.dto;

import java.util.List;

/** 업로드 URL 발급 요청 — robotId(선택, 없으면 앱이 "web" 처리)와 파일별 확장자 목록. */
public record UploadUrlRequest(String robotId, List<FileSpec> files) {
  /** 개별 파일 스펙 — 확장자만 제공(키/파일명은 앱이 생성). */
  public record FileSpec(String ext) {}
}
```
`UploadTarget.java`:
```java
package com.smartfirehub.file.dto;

/** 발급된 업로드 대상 — 앱이 생성한 오브젝트 키 + 클라이언트가 PUT할 presigned URL. */
public record UploadTarget(String key, String uploadUrl) {}
```
`UploadUrlResponse.java`:
```java
package com.smartfirehub.file.dto;

import java.util.List;

/** 업로드 URL 발급 응답 — 대상 목록 + 만료(초). */
public record UploadUrlResponse(List<UploadTarget> targets, int expiresInSeconds) {}
```

- [ ] **Step 6: 컨트롤러 테스트 추가 (실패 확인용)**

`FileObjectControllerTest.java` import 추가:
```java
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import com.smartfirehub.file.dto.UploadUrlRequest;
import com.smartfirehub.file.service.ObjectKeyGenerator;
import com.smartfirehub.global.security.RequirePermission;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
```
mvc 필드의 `standaloneSetup`을 3-arg 생성자로 교체(키 생성기 주입):
```java
  MockMvc mvc =
      org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
              new FileObjectController(storage, configRepo, new ObjectKeyGenerator()))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
```
테스트 메서드 추가:
```java
  /** upload-urls: 파일 N개 → 대상 N개, robotId/ext 정제 후 키가 프리픽스 하위 규약을 만족한다. */
  @Test
  void createUploadUrls_generatesKeysUnderPrefixAndDelegates() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    when(storage.defaultUploadPresignExpiry()).thenReturn(900);
    when(storage.presignedPutUrl(eq("firehub-files"), any(), eq(900)))
        .thenAnswer(
            inv -> new PresignedUrlResponse("http://minio/" + inv.getArgument(1) + "?sig=put", 900));

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"robotId\":\"Robot 01!\",\"files\":[{\"ext\":\"JPG\"},{\"ext\":\"png\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresInSeconds").value(900))
        .andExpect(jsonPath("$.targets.length()").value(2))
        .andExpect(
            jsonPath("$.targets[0].key")
                .value(matchesPattern("equip/robot-01/\\d{4}-\\d{2}-\\d{2}/[0-9a-f-]{36}\\.jpg")))
        .andExpect(jsonPath("$.targets[0].uploadUrl").value(containsString("sig=put")));
  }

  /** files가 비면 400. */
  @Test
  void createUploadUrls_rejectsEmptyFiles() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[]}"))
        .andExpect(status().is4xxClientError());
  }

  /** files가 1000개를 초과하면 400. */
  @Test
  void createUploadUrls_rejectsOverBatchLimit() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    String one = "{\"ext\":\"jpg\"}";
    String files = (one + ",").repeat(1001);
    files = files.substring(0, files.length() - 1); // 마지막 콤마 제거 → 1001개
    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[" + files + "]}"))
        .andExpect(status().is4xxClientError());
  }

  /** 비FILE 데이터셋(config 없음)이면 400. */
  @Test
  void createUploadUrls_rejectsForNonFileDataset() throws Exception {
    when(configRepo.findByDatasetId(99L)).thenReturn(Optional.empty());
    mvc.perform(
            post("/api/v1/datasets/99/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[{\"ext\":\"jpg\"}]}"))
        .andExpect(status().is4xxClientError());
  }

  /** 권한 강제는 통합영역이므로, 최소한 @RequirePermission("dataset:write") 애노테이션이 선언돼 있는지 잠근다. */
  @Test
  void createUploadUrls_requiresDatasetWritePermission() throws Exception {
    var method =
        FileObjectController.class.getMethod("createUploadUrls", Long.class, UploadUrlRequest.class);
    RequirePermission ann = method.getAnnotation(RequirePermission.class);
    assertThat(ann).isNotNull();
    assertThat(ann.value()).isEqualTo("dataset:write");
  }
```

- [ ] **Step 7: 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.controller.FileObjectControllerTest"`
Expected: FAIL — `createUploadUrls`/3-arg 생성자 미존재(컴파일 에러).

- [ ] **Step 8: 컨트롤러에 엔드포인트 추가**

import 추가:
```java
import com.smartfirehub.file.dto.UploadTarget;
import com.smartfirehub.file.dto.UploadUrlRequest;
import com.smartfirehub.file.dto.UploadUrlRequest.FileSpec;
import com.smartfirehub.file.dto.UploadUrlResponse;
import com.smartfirehub.file.service.ObjectKeyGenerator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
```
필드/생성자에 `ObjectKeyGenerator` 주입:
```java
  private final FileObjectStorageService storage;
  private final FileDatasetConfigRepository configRepo;
  private final ObjectKeyGenerator keyGenerator;

  // 업로드 배치 상한: 대량 유입 대비 1회 다건 발급을 허용하되 남용을 막는다.
  private static final int MAX_UPLOAD_BATCH = 1000;

  public FileObjectController(
      FileObjectStorageService storage,
      FileDatasetConfigRepository configRepo,
      ObjectKeyGenerator keyGenerator) {
    this.storage = storage;
    this.configRepo = configRepo;
    this.keyGenerator = keyGenerator;
  }
```
`config(...)` 헬퍼 위(또는 presignedUrl 아래)에 엔드포인트 추가:
```java
  /** 업로드용 presigned PUT URL을 배치로 발급한다. 앱이 키를 생성하여 프리픽스 격리·규약을 강제한다. */
  @PostMapping("/upload-urls")
  @RequirePermission("dataset:write")
  public ResponseEntity<UploadUrlResponse> createUploadUrls(
      @PathVariable Long datasetId, @RequestBody UploadUrlRequest request) {
    FileDatasetConfig cfg = config(datasetId);
    List<FileSpec> files = request.files();
    // files는 1개 이상 MAX_UPLOAD_BATCH 이하만 허용(0개/초과는 잘못된 요청).
    if (files == null || files.isEmpty() || files.size() > MAX_UPLOAD_BATCH) {
      throw new IllegalArgumentException("files는 1개 이상 " + MAX_UPLOAD_BATCH + "개 이하여야 합니다");
    }
    int expiry = storage.defaultUploadPresignExpiry();
    List<UploadTarget> targets = new ArrayList<>();
    for (FileSpec f : files) {
      // 앱이 키 생성(프리픽스 격리 + 규약 강제) → 해당 키에 대한 presigned PUT URL 발급.
      String key = keyGenerator.generateKey(cfg.prefix(), request.robotId(), f.ext());
      String url = storage.presignedPutUrl(cfg.bucket(), key, expiry).url();
      targets.add(new UploadTarget(key, url));
    }
    return ResponseEntity.ok(new UploadUrlResponse(targets, expiry));
  }
```

- [ ] **Step 9: 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.controller.FileObjectControllerTest" --tests "com.smartfirehub.file.service.ObjectKeyGeneratorTest"`
Expected: PASS (컨트롤러 기존 4개 + 신규 5개, 키생성 3개).

- [ ] **Step 10: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/file/service/ObjectKeyGenerator.java \
        apps/firehub-api/src/main/java/com/smartfirehub/file/dto/UploadUrlRequest.java \
        apps/firehub-api/src/main/java/com/smartfirehub/file/dto/UploadTarget.java \
        apps/firehub-api/src/main/java/com/smartfirehub/file/dto/UploadUrlResponse.java \
        apps/firehub-api/src/main/java/com/smartfirehub/file/controller/FileObjectController.java \
        apps/firehub-api/src/test/java/com/smartfirehub/file/service/ObjectKeyGeneratorTest.java \
        apps/firehub-api/src/test/java/com/smartfirehub/file/controller/FileObjectControllerTest.java
git commit -m "feat(file): 업로드 URL 배치 발급 엔드포인트 + 키 생성기"
```

---

### Task 3: 프론트엔드 업로드(API + 훅 + UI + E2E)

**Files:**
- Modify: `apps/firehub-web/src/api/objects.ts`
- Modify: `apps/firehub-web/src/hooks/queries/useObjects.ts`
- Modify: `apps/firehub-web/src/pages/data/tabs/DatasetObjectsTab.tsx`
- Test: `apps/firehub-web/e2e/flows/file-dataset-browser.spec.ts`

**Interfaces:**
- Consumes: `POST /datasets/{id}/objects/upload-urls` → `{ targets: {key,uploadUrl}[], expiresInSeconds }` (Task 2).
- Produces: `objectsApi.requestUploadUrls`, `putToPresignedUrl`, `extOf`; `useUploadObjects(datasetId, robotId?)` 뮤테이션; `DatasetObjectsTab`의 드롭존.

- [ ] **Step 1: `api/objects.ts`에 업로드 API 추가**

파일 상단 import에 axios 추가, 파일 하단을 아래로 확장:
```ts
import axios from 'axios';
import { client } from './client';
```
`PresignedUrlResponse` 인터페이스 아래에 추가:
```ts
/** 업로드 대상 — 앱이 생성한 키 + 클라이언트가 PUT할 presigned URL */
export interface UploadTarget {
  key: string;
  uploadUrl: string;
}

/** 업로드 URL 발급 응답 — 대상 목록 + 만료(초) */
export interface UploadUrlResponse {
  targets: UploadTarget[];
  expiresInSeconds: number;
}
```
`objectsApi` 객체에 메서드 추가:
```ts
  // presigned PUT URL 배치 발급 (앱이 키 생성)
  requestUploadUrls: (datasetId: number, body: { robotId?: string; files: { ext: string }[] }) =>
    client.post<UploadUrlResponse>(`/datasets/${datasetId}/objects/upload-urls`, body),
```
`objectsApi` 정의 아래에 헬퍼 2개 추가:
```ts
/** presigned PUT URL로 파일 바이트를 MinIO에 직접 업로드한다(앱 baseURL/인터셉터 미경유). */
export async function putToPresignedUrl(uploadUrl: string, file: File): Promise<void> {
  await axios.put(uploadUrl, file, {
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
  });
}

/** 파일명에서 확장자 추출(소문자, 점 제외). 확장자가 없으면 'bin'. */
export function extOf(file: File): string {
  const i = file.name.lastIndexOf('.');
  return i >= 0 ? file.name.slice(i + 1).toLowerCase() : 'bin';
}
```

- [ ] **Step 2: `useObjects.ts`에 업로드 뮤테이션 추가**

import 라인 교체/확장:
```ts
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { extOf, objectsApi, putToPresignedUrl } from '../../api/objects';
```
파일 하단에 추가:
```ts
/**
 * FILE 데이터셋 업로드 뮤테이션.
 * ① upload-urls로 파일 수만큼 presigned PUT 대상 발급 → ② 각 파일을 MinIO에 직접 PUT →
 * ③ 성공 시 오브젝트 목록 쿼리를 무효화하여 새 오브젝트를 재조회한다.
 */
export function useUploadObjects(datasetId: number, robotId = 'web') {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (files: File[]) => {
      const { data } = await objectsApi.requestUploadUrls(datasetId, {
        robotId,
        files: files.map((f) => ({ ext: extOf(f) })),
      });
      // 응답 targets는 요청 files 순서를 유지하므로 인덱스로 짝지어 업로드한다.
      await Promise.all(data.targets.map((t, i) => putToPresignedUrl(t.uploadUrl, files[i])));
      return data.targets.length;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['datasets', datasetId, 'objects'] });
    },
  });
}
```

- [ ] **Step 3: `DatasetObjectsTab.tsx`에 드래그앤드롭 업로드 UI 추가**

파일 전체를 아래로 교체:
```tsx
import { useRef, useState } from 'react';

import { useObjectList, useUploadObjects } from '../../../hooks/queries/useObjects';
import { ObjectThumbnail } from '../components/ObjectThumbnail';

/** FILE 데이터셋 오브젝트 브라우저 탭 — 업로드(드래그앤드롭) + presigned 썸네일 그리드 + 무한스크롤. */
export function DatasetObjectsTab({ datasetId }: { datasetId: number }) {
  const { data, isLoading, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useObjectList(datasetId);
  const upload = useUploadObjects(datasetId);
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);

  // 파일 선택/드롭 공통 처리 — 1개 이상일 때만 업로드 시작.
  const handleFiles = (files: FileList | null) => {
    if (files && files.length > 0) upload.mutate(Array.from(files));
  };

  const items = data?.pages.flatMap((p) => p.objects) ?? [];

  return (
    <div className="space-y-4 p-2">
      {/* 업로드 드롭존: 클릭 시 파일 선택, 드롭 시 즉시 업로드 */}
      <div
        role="button"
        tabIndex={0}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click();
        }}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          handleFiles(e.dataTransfer.files);
        }}
        className={`cursor-pointer rounded-md border border-dashed p-6 text-center text-sm ${
          dragOver ? 'border-primary bg-accent' : 'text-muted-foreground'
        }`}
      >
        {upload.isPending
          ? '업로드 중…'
          : upload.isError
            ? '업로드 실패 — 다시 시도하세요'
            : '파일을 드래그하거나 클릭하여 업로드'}
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
      </div>

      {isLoading ? (
        <div className="p-6 text-muted-foreground">불러오는 중…</div>
      ) : items.length === 0 ? (
        <div className="p-6 text-muted-foreground">오브젝트가 없습니다.</div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
            {items.map((o) => (
              <ObjectThumbnail key={o.key} datasetId={datasetId} objectKey={o.key} size={o.size} />
            ))}
          </div>
          {hasNextPage && (
            <button
              type="button"
              onClick={() => fetchNextPage()}
              disabled={isFetchingNextPage}
              className="mx-auto block rounded-md border px-4 py-2 text-sm hover:bg-accent"
            >
              {isFetchingNextPage ? '불러오는 중…' : '더 보기'}
            </button>
          )}
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 4: 타입체크 확인**

Run: `cd apps/firehub-web && pnpm exec tsc --noEmit`
Expected: PASS (관련 파일에 타입 에러 없음).

- [ ] **Step 5: 업로드 E2E 작성**

`e2e/flows/file-dataset-browser.spec.ts`의 `test.describe(...)` 블록 안(마지막 test 뒤)에 추가:
```ts
  test('파일을 드롭하면 presigned PUT으로 업로드하고 목록을 갱신한다', async ({
    authenticatedPage: page,
  }) => {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    // 업로드 성공 후 재조회 시 새 오브젝트가 보이도록 목록 응답을 준비한다.
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [{ key: 'equip/web/2026-07-20/u1.jpg', size: 1024, lastModified: null }],
      nextToken: null,
      hasMore: false,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects/url`, {
      url: 'https://example.com/u1.jpg',
      expiresInSeconds: 300,
    });
    // upload-urls 발급 mock (요청 캡처)
    const cap = await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/objects/upload-urls`,
      {
        targets: [
          { key: 'equip/web/2026-07-20/u1.jpg', uploadUrl: 'https://minio.example.com/put/u1.jpg' },
        ],
        expiresInSeconds: 900,
      },
      { capture: true },
    );
    // presigned PUT(외부 MinIO) mock — 앱을 경유하지 않는 직접 PUT
    await page.route('https://minio.example.com/**', (route) =>
      route.fulfill({ status: 200, body: '' }),
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();

    // 숨겨진 파일 입력에 파일 주입 → 업로드 트리거
    await page.locator('input[type="file"]').setInputFiles({
      name: 'photo.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.from('fake-bytes'),
    });

    // upload-urls 요청이 robotId=web, files=[{ext:'jpg'}] 로 전송됐는지 확인
    const req = await cap.waitForRequest();
    expect(req.payload).toMatchObject({ robotId: 'web', files: [{ ext: 'jpg' }] });

    // 업로드 후 목록 재조회로 새 오브젝트 썸네일이 노출된다
    await expect(page.locator('img[alt="u1.jpg"]')).toHaveAttribute(
      'src',
      'https://example.com/u1.jpg',
    );
  });
```

- [ ] **Step 6: E2E 통과 확인**

Run: `cd apps/firehub-web && pnpm exec playwright test e2e/flows/file-dataset-browser.spec.ts`
Expected: PASS (기존 2개 + 신규 1개).
> playwright kill-all 금지 — 실패 시 해당 세션만 종료하고 다른 브라우저는 건드리지 않는다.

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-web/src/api/objects.ts \
        apps/firehub-web/src/hooks/queries/useObjects.ts \
        apps/firehub-web/src/pages/data/tabs/DatasetObjectsTab.tsx \
        apps/firehub-web/e2e/flows/file-dataset-browser.spec.ts
git commit -m "feat(web): FILE 데이터셋 오브젝트 브라우저 드래그앤드롭 업로드"
```

---

### Task 4: dev MinIO CORS + 문서

**Files:**
- Modify: `docker-compose.yml` (minio 서비스)
- Modify: `docker-compose.prod.yml` (minio 서비스; 존재 시)
- Modify: `docs/superpowers/specs/2026-07-20-file-object-dataset-poc-design.md` (§10)

**Interfaces:**
- Consumes: 브라우저 직접 PUT(Task 3).
- Produces: dev MinIO가 웹 오리진에 대해 CORS 허용.

- [ ] **Step 1: dev MinIO에 CORS 허용 환경변수 추가**

`docker-compose.yml`의 `minio` 서비스 `environment` 블록에 추가(MinIO 서버 전역 CORS 허용 오리진):
```yaml
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
      # 브라우저가 presigned PUT/GET으로 MinIO에 직접 접근할 수 있도록 웹 오리진 CORS 허용.
      MINIO_API_CORS_ALLOW_ORIGIN: ${MINIO_CORS_ALLOW_ORIGIN:-http://localhost:5173}
```

- [ ] **Step 2: MinIO 재기동 후 CORS 프리플라이트 검증**

Run:
```bash
docker compose up -d minio
sleep 3
curl -s -o /dev/null -w "%{http_code} %header{access-control-allow-origin}\n" \
  -X OPTIONS "http://localhost:9000/firehub-files/test.jpg" \
  -H "Origin: http://localhost:5173" \
  -H "Access-Control-Request-Method: PUT"
```
Expected: `200 http://localhost:5173` (또는 `*`). `access-control-allow-origin` 헤더가 웹 오리진을 반영하면 통과.
> `%header{...}`가 지원되지 않는 curl 버전이면 `curl -i -X OPTIONS ...`로 응답 헤더에 `Access-Control-Allow-Origin`이 있는지 육안 확인한다.

- [ ] **Step 3: prod compose에 CORS 반영(minio 서비스가 있으면)**

`docker-compose.prod.yml`에 `minio` 서비스가 있으면 `environment`에 추가:
```yaml
      # prod: 브라우저 도달 가능한 공개 웹 오리진을 CORS로 허용해야 썸네일/업로드가 동작한다.
      MINIO_API_CORS_ALLOW_ORIGIN: ${MINIO_CORS_ALLOW_ORIGIN:?web origin required}
```
minio 서비스가 prod compose에 없으면 이 스텝은 건너뛰고 그 사실을 커밋 메시지에 기록한다.

- [ ] **Step 4: 부모 스펙 §10 알려진 한계에 PUT CORS 반영**

`docs/superpowers/specs/2026-07-20-file-object-dataset-poc-design.md`의 §10에서 CORS 관련 항목을 아래로 갱신(GET뿐 아니라 PUT 포함, dev 자동 구성 명시):
```
- **CORS**: 브라우저가 presigned GET/PUT으로 MinIO에 직접 접근하려면 버킷/서버 CORS 설정이 필요하다. dev는 `MINIO_API_CORS_ALLOW_ORIGIN`(docker-compose)로 웹 오리진(`http://localhost:5173`)을 허용해 해소했다. prod는 `MINIO_ENDPOINT`가 브라우저 도달 가능한 공개 호스트여야 하고, 같은 방식으로 공개 웹 오리진을 CORS 허용해야 브라우저 업로드/썸네일이 동작한다(로봇 서버-투-서버 PUT은 CORS 무관).
```

- [ ] **Step 5: 커밋**

```bash
git add docker-compose.yml docker-compose.prod.yml \
        docs/superpowers/specs/2026-07-20-file-object-dataset-poc-design.md
git commit -m "chore(minio): dev CORS 허용 + prod/스펙 PUT CORS 반영"
```

---

## Self-Review (작성자 체크 완료)

- **Spec coverage**: §4 API→Task2, §5 키 생성→Task2, §6 백엔드(props/서비스/DTO/컨트롤러)→Task1·2, §7 프론트→Task3, §8 CORS→Task4, §9 테스트→각 Task, §10 리스크(CORS)→Task4. 누락 없음.
- **Placeholder scan**: 모든 코드 스텝에 실제 코드/명령/기대출력 포함. TODO/TBD 없음.
- **Type consistency**: `MinioProperties` 6-arg는 Task1에서 정의·Task1 테스트에서 사용. `presignedPutUrl`/`defaultUploadPresignExpiry` Task1 정의→Task2 소비. DTO(`UploadUrlRequest.FileSpec`, `UploadTarget`, `UploadUrlResponse`) Task2 정의→Task3 프론트 타입과 필드명 일치(`targets`,`key`,`uploadUrl`,`expiresInSeconds`). 프론트 `requestUploadUrls`/`putToPresignedUrl`/`extOf` Task3 정의→`useUploadObjects` 소비. 쿼리키 `['datasets', datasetId, 'objects']`는 기존 `useObjectList`와 일치(무효화 정합).

package com.smartfirehub.file.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.file.dto.ObjectFolderResponse;
import com.smartfirehub.file.dto.ObjectItemResponse;
import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import com.smartfirehub.file.dto.UploadUrlRequest;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.repository.FileDatasetConfigRepository.FileDatasetConfig;
import com.smartfirehub.file.service.FileObjectStorageService;
import com.smartfirehub.file.service.ObjectKeyGenerator;
import com.smartfirehub.global.exception.GlobalExceptionHandler;
import com.smartfirehub.global.security.RequirePermission;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /objects 목록·presigned 엔드포인트가 데이터셋 config의 버킷/프리픽스를 사용해 서비스에 위임하는지 검증한다. (standalone MockMvc —
 * 보안필터/권한은 통합영역)
 */
class FileObjectControllerTest {

  final FileObjectStorageService storage = org.mockito.Mockito.mock(FileObjectStorageService.class);
  final FileDatasetConfigRepository configRepo =
      org.mockito.Mockito.mock(FileDatasetConfigRepository.class);

  // IllegalArgumentException(격리 위반/미존재 config)이 GlobalExceptionHandler를 통해
  // 400으로 매핑되는지도 검증하기 위해 controller advice를 등록한다.
  MockMvc mvc =
      org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
              new FileObjectController(
                  storage,
                  configRepo,
                  new ObjectKeyGenerator(),
                  // inline(기본) 경로는 가드를 부르지 않는다 — 실제 attachment 판정은 DataExportPolicyTest 가 검증.
                  org.mockito.Mockito.mock(
                      com.smartfirehub.securitylevel.access.DatasetAccessGuard.class)))
          .setControllerAdvice(
              new GlobalExceptionHandler(
                  new com.smartfirehub.global.platformdb.postgres.PostgresPlatformDbErrors()))
          .build();

  @Test
  void listObjects_usesDatasetConfigPrefix() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    // 기본 요청(쿼리 없음)은 루트 폴더("") 폴더 모드로 위임된다(WD-761).
    when(storage.listFolder(eq("firehub-files"), eq("equip/"), eq(""), any(), anyInt()))
        .thenReturn(
            new ObjectListResponse(
                List.of(
                    new ObjectItemResponse("equip/a.jpg", "a.jpg", 123L, "2026-07-20T00:00:00Z")),
                null,
                false));

    mvc.perform(get("/api/v1/datasets/7/objects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objects[0].key").value("equip/a.jpg"))
        // 표시명(name)은 prefix를 제외한 상대경로로 함께 내려간다.
        .andExpect(jsonPath("$.objects[0].name").value("a.jpg"));
  }

  @Test
  void presignedUrl_delegatesWithConfiguredExpiry() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    // 컨트롤러가 하드코딩값이 아닌 storage.defaultPresignExpiry()를 사용해 위임하는지 검증한다.
    when(storage.defaultPresignExpiry()).thenReturn(900);
    when(storage.presignedGetUrl(eq("firehub-files"), eq("equip/a.jpg"), eq(900), eq("inline")))
        .thenReturn(new PresignedUrlResponse("http://minio/equip/a.jpg?sig=x", 900));

    mvc.perform(get("/api/v1/datasets/7/objects/url").param("key", "equip/a.jpg"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("http://minio/equip/a.jpg?sig=x"))
        .andExpect(jsonPath("$.expiresInSeconds").value(900));
  }

  @Test
  void presignedUrl_rejectsKeyOutsideDatasetPrefix() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));

    // 다른 데이터셋 프리픽스(other/) 하위 키로 접근 시도 → 격리 위반으로 거부되어야 한다.
    mvc.perform(get("/api/v1/datasets/7/objects/url").param("key", "other/secret.jpg"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void config_missingForNonFileDataset_throws() throws Exception {
    when(configRepo.findByDatasetId(99L)).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/datasets/99/objects")).andExpect(status().is4xxClientError());
  }

  /** upload-urls: 파일 N개 → 대상 N개, 키는 "&lt;prefix&gt;&lt;파일명&gt;"(S3 방식)로 프리픽스 하위에 생성된다. */
  @Test
  void createUploadUrls_generatesKeysUnderPrefixAndDelegates() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    when(storage.defaultUploadPresignExpiry()).thenReturn(900);
    when(storage.presignedPutUrl(eq("firehub-files"), any(), eq(900)))
        .thenAnswer(
            inv ->
                new PresignedUrlResponse("http://minio/" + inv.getArgument(1) + "?sig=put", 900));

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"files\":[{\"filename\":\"photo.jpg\"},{\"filename\":\"my report.png\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresInSeconds").value(900))
        .andExpect(jsonPath("$.targets.length()").value(2))
        // 원본 파일명이 그대로 prefix 하위 키가 된다.
        .andExpect(jsonPath("$.targets[0].key").value("equip/photo.jpg"))
        .andExpect(jsonPath("$.targets[1].key").value("equip/my report.png"))
        .andExpect(jsonPath("$.targets[0].uploadUrl").value(containsString("sig=put")));
  }

  /** upload-urls: 폴더 업로드 시 상대경로가 prefix 하위 키에 그대로 보존된다(구조 보존). */
  @Test
  void createUploadUrls_preservesFolderStructureInKey() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    when(storage.defaultUploadPresignExpiry()).thenReturn(900);
    when(storage.presignedPutUrl(eq("firehub-files"), any(), eq(900)))
        .thenAnswer(
            inv ->
                new PresignedUrlResponse("http://minio/" + inv.getArgument(1) + "?sig=put", 900));

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[{\"filename\":\"site-A/2026/cam1/img001.jpg\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets[0].key").value("equip/site-A/2026/cam1/img001.jpg"));
  }

  /**
   * WD-776: 파일 하나의 검증 실패(정제 후 빈 이름 "..", traversal "sub/../../x")는 묶음 전체 400 대신 그 파일 자리에만 error 를
   * 싣고 presign 하지 않는다 — 나머지는 그대로 발급한다. 실패한 파일은 키를 만들지 않으므로 prefix 격리는 그대로다.
   */
  @Test
  void createUploadUrls_reportsInvalidFilenamesAsPerFileFailuresAndIssuesRest() throws Exception {
    givenEquipConfig();
    givenPresignedPut();

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"files\":[{\"filename\":\"a.txt\"},{\"filename\":\"..\"},"
                        + "{\"filename\":\"sub/../../etc/passwd\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets.length()").value(3))
        .andExpect(jsonPath("$.targets[0].key").value("equip/a.txt"))
        .andExpect(jsonPath("$.targets[0].error").doesNotExist())
        .andExpect(jsonPath("$.targets[1].key").doesNotExist())
        .andExpect(jsonPath("$.targets[1].uploadUrl").doesNotExist())
        .andExpect(jsonPath("$.targets[1].error").value(containsString("유효한 파일명")))
        .andExpect(jsonPath("$.targets[2].key").doesNotExist())
        .andExpect(jsonPath("$.targets[2].error").value(containsString("유효한 파일명")));

    verify(storage, times(1)).presignedPutUrl(any(), any(), anyInt());
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
    String one = "{\"filename\":\"a.jpg\"}";
    String files = (one + ",").repeat(1001);
    files = files.substring(0, files.length() - 1); // 마지막 콤마 제거 → 1001개
    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[" + files + "]}"))
        .andExpect(status().is4xxClientError());
  }

  /** files 배열에 null 원소가 섞이면 f.ext() 에서 NPE(500) 대신 400으로 응답해야 한다. */
  @Test
  void createUploadUrls_rejectsNullElementInFiles() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[null]}"))
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
        FileObjectController.class.getMethod(
            "createUploadUrls", Long.class, UploadUrlRequest.class);
    RequirePermission ann = method.getAnnotation(RequirePermission.class);
    assertThat(ann).isNotNull();
    // RequirePermission.value()는 String[] — 단일 값 "dataset:write" 배열인지 확인한다.
    assertThat(ann.value()).containsExactly("dataset:write");
  }

  // ---------------------------------------------------------------------------------------
  // 목록 모드 분기(WD-761): 기본 = 폴더 모드, q 가 있으면 검색, recursive=true 면 기존 평면 목록.
  // ---------------------------------------------------------------------------------------

  /** 기본 요청은 폴더 모드로 위임하고, path 는 정규화(선행·중복 '/' 흡수, 끝 '/')한 값으로 넘긴다. */
  @Test
  void list_defaultDelegatesToFolderModeWithNormalizedPath() throws Exception {
    givenEquipConfig();
    when(storage.listFolder("firehub-files", "equip/", "제품/Tibero7/", null, 50))
        .thenReturn(
            new ObjectListResponse(
                List.of(
                    new ObjectItemResponse("equip/제품/Tibero7/a.jpg", "제품/Tibero7/a.jpg", 1L, null)),
                List.of(new ObjectFolderResponse("제품/Tibero7/sub/", "sub")),
                null,
                false,
                false));

    mvc.perform(get("/api/v1/datasets/7/objects").param("path", "/제품//Tibero7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.folders[0].path").value("제품/Tibero7/sub/"))
        .andExpect(jsonPath("$.folders[0].name").value("sub"))
        .andExpect(jsonPath("$.objects[0].name").value("제품/Tibero7/a.jpg"))
        .andExpect(jsonPath("$.truncated").value(false));
  }

  /** path 가 없으면 루트("")로 폴더 모드 위임. */
  @Test
  void list_withoutPathListsRootFolder() throws Exception {
    givenEquipConfig();
    when(storage.listFolder("firehub-files", "equip/", "", "equip/a/", 20))
        .thenReturn(new ObjectListResponse(List.of(), null, false));

    mvc.perform(get("/api/v1/datasets/7/objects").param("token", "equip/a/").param("size", "20"))
        .andExpect(status().isOk());

    verify(storage).listFolder("firehub-files", "equip/", "", "equip/a/", 20);
  }

  /** path 에 '..' 세그먼트가 있으면 데이터셋 prefix 밖으로 나가려는 시도 — 400, 스토리지는 부르지 않는다. */
  @Test
  void list_rejectsTraversalPath() throws Exception {
    givenEquipConfig();

    mvc.perform(get("/api/v1/datasets/7/objects").param("path", "../x"))
        .andExpect(status().isBadRequest());
    // 퍼센트 인코딩된 '..'(%2e%2e)도 Spring 이 디코드한 뒤 같은 규칙으로 거부된다.
    mvc.perform(get(URI.create("/api/v1/datasets/7/objects?path=%2e%2e%2Fother")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/datasets/7/objects").param("path", "a\\..\\..\\b"))
        .andExpect(status().isBadRequest());

    verify(storage, never()).listFolder(any(), any(), any(), any(), anyInt());
  }

  /** q 가 공백이 아니면 검색 모드로 위임한다 — 앞뒤 공백을 떼고, path 는 무시, 훑기 상한은 SEARCH_SCAN_LIMIT. */
  @Test
  void list_withQueryDelegatesToSearch() throws Exception {
    givenEquipConfig();
    when(storage.searchObjects(
            "firehub-files", "equip/", "Tibero", null, 50, FileObjectController.SEARCH_SCAN_LIMIT))
        .thenReturn(new ObjectListResponse(List.of(), List.of(), null, false, true));

    mvc.perform(get("/api/v1/datasets/7/objects").param("q", " Tibero ").param("path", "a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.truncated").value(true))
        .andExpect(jsonPath("$.hasMore").value(false));

    assertThat(FileObjectController.SEARCH_SCAN_LIMIT).isEqualTo(10_000);
    verify(storage, never()).listFolder(any(), any(), any(), any(), anyInt());
  }

  /** 공백만인 q 는 검색이 아니다 — 폴더 모드. */
  @Test
  void list_blankQueryIsFolderMode() throws Exception {
    givenEquipConfig();
    when(storage.listFolder("firehub-files", "equip/", "", null, 50))
        .thenReturn(new ObjectListResponse(List.of(), null, false));

    mvc.perform(get("/api/v1/datasets/7/objects").param("q", "   ")).andExpect(status().isOk());

    verify(storage).listFolder("firehub-files", "equip/", "", null, 50);
    verify(storage, never()).searchObjects(any(), any(), any(), any(), anyInt(), anyInt());
  }

  /** recursive=true 면 기존 재귀 평면 목록(listObjects)으로 위임한다 — AI 에이전트·채팅 위젯 호환. */
  @Test
  void list_recursiveDelegatesToFlatListing() throws Exception {
    givenEquipConfig();
    when(storage.listObjects("firehub-files", "equip/", null, 50))
        .thenReturn(
            new ObjectListResponse(
                List.of(new ObjectItemResponse("equip/a/b.jpg", "a/b.jpg", 1L, null)),
                null,
                false));

    mvc.perform(get("/api/v1/datasets/7/objects").param("recursive", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objects[0].name").value("a/b.jpg"))
        // 3인자 응답은 folders=[] · truncated=false 로 직렬화된다.
        .andExpect(jsonPath("$.folders.length()").value(0))
        .andExpect(jsonPath("$.truncated").value(false));

    verify(storage, never()).listFolder(any(), any(), any(), any(), anyInt());
  }

  /** 목록 권한은 그대로 dataset:read 다. */
  @Test
  void list_requiresDatasetReadPermission() {
    Method list =
        Arrays.stream(FileObjectController.class.getMethods())
            .filter(m -> m.getName().equals("list"))
            .findFirst()
            .orElseThrow();
    RequirePermission ann = list.getAnnotation(RequirePermission.class);
    assertThat(ann).isNotNull();
    assertThat(ann.value()).containsExactly("dataset:read");
  }

  // ---------------------------------------------------------------------------------------
  // 업로드 대상 폴더(WD-761): 본문 path 가 키의 prefix 바로 뒤에 붙는다.
  // ---------------------------------------------------------------------------------------

  /** upload-urls 의 path 가 키 앞에 붙는다: "&lt;prefix&gt;&lt;정규화한 path&gt;&lt;파일 상대경로&gt;". */
  @Test
  void createUploadUrls_prependsFolderPathToKeys() throws Exception {
    givenEquipConfig();
    givenPresignedPut();

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"/제품/Tibero7\",\"files\":[{\"filename\":\"a.jpg\"},"
                        + "{\"filename\":\"sub/b.jpg\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets[0].key").value("equip/제품/Tibero7/a.jpg"))
        // 폴더 업로드의 하위 상대경로는 지정 폴더 아래 그대로 유지된다.
        .andExpect(jsonPath("$.targets[1].key").value("equip/제품/Tibero7/sub/b.jpg"));
  }

  /** upload-urls 의 path 에 '..' 가 있으면 400, presigned URL 은 발급하지 않는다. */
  @Test
  void createUploadUrls_rejectsTraversalFolderPath() throws Exception {
    givenEquipConfig();
    givenPresignedPut();

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"a/../../other\",\"files\":[{\"filename\":\"x.jpg\"}]}"))
        .andExpect(status().isBadRequest());

    verify(storage, never()).presignedPutUrl(any(), any(), anyInt());
  }

  /**
   * WD-776: path + 파일 상대경로 합이 900B 를 넘으면 자르지 않고 그 파일만 실패로 싣는다(같은 순서 자리에 key·uploadUrl 없이 error) —
   * 잘라 올리면 앞부분이 같은 이름끼리 같은 키가 되어 말없이 덮어쓰고 화면은 성공으로 보인다. 묶음의 나머지는 발급한다.
   */
  @Test
  void createUploadUrls_rejectsTooLongNameInFolderPerFileAndIssuesRest() throws Exception {
    givenEquipConfig();
    givenPresignedPut();
    // 세그먼트는 모두 255B 이하(MinIO 세그먼트 상한) — 합계 900B 규칙만 본다. 폴더 500B + 파일 405B = 905B.
    String folder = ("a".repeat(249) + "/").repeat(2);
    String filename = "x".repeat(200) + "/" + "b".repeat(200) + ".jpg";

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\""
                        + folder
                        + "\",\"files\":[{\"filename\":\""
                        + filename
                        + "\"},{\"filename\":\"short.txt\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets.length()").value(2))
        .andExpect(jsonPath("$.targets[0].key").doesNotExist())
        .andExpect(jsonPath("$.targets[0].uploadUrl").doesNotExist())
        .andExpect(jsonPath("$.targets[0].error").value(containsString("너무 깁니다")))
        .andExpect(jsonPath("$.targets[1].key").value("equip/" + folder + "short.txt"))
        .andExpect(jsonPath("$.targets[1].uploadUrl").value(containsString("sig=put")))
        .andExpect(jsonPath("$.targets[1].error").doesNotExist());

    // 실패한 파일은 presign 하지 않는다 — 발급은 나머지 1건뿐.
    verify(storage, times(1)).presignedPutUrl(any(), any(), anyInt());
  }

  /** WD-776: '\' 가 든 폴더·공백뿐인 폴더 경로는 바뀌지 않고 그대로 목록 prefix 로 간다. */
  @Test
  void list_preservesBackslashAndBlankFolderPath() throws Exception {
    givenEquipConfig();
    when(storage.listFolder(any(), any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListResponse(List.of(), null, false));

    mvc.perform(get("/api/v1/datasets/7/objects").param("path", "report\\2024"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/datasets/7/objects").param("path", " ")).andExpect(status().isOk());

    verify(storage).listFolder("firehub-files", "equip/", "report\\2024/", null, 50);
    verify(storage).listFolder("firehub-files", "equip/", " /", null, 50);
  }

  /** WD-776: 그 폴더에서 올리면 키도 같은 폴더 아래다. */
  @Test
  void createUploadUrls_usesBackslashAndBlankFolderPathInKey() throws Exception {
    givenEquipConfig();
    givenPresignedPut();

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"report\\\\2024/ \",\"files\":[{\"filename\":\"a.jpg\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets[0].key").value("equip/report\\2024/ /a.jpg"));
  }

  /**
   * WD-776: MinIO 는 키의 한 세그먼트가 255B 를 넘으면 PUT 을 400(XMinioInvalidObjectName)으로 거부한다. 폴더 안에서 255B 를
   * 넘는 파일명(한글 100자 + ".txt" = 304B)은 자르지 않고 그 파일만 error 로 싣고, 묶음의 다른 파일은 발급한다.
   */
  @Test
  void createUploadUrls_rejectsFilenameSegmentOver255BytesInFolderPerFile() throws Exception {
    givenEquipConfig();
    givenPresignedPut();
    String longName = "다".repeat(100) + ".txt";

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"report\\\\2024\",\"files\":[{\"filename\":\""
                        + longName
                        + "\"},{\"filename\":\"ok.txt\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets.length()").value(2))
        .andExpect(jsonPath("$.targets[0].key").doesNotExist())
        .andExpect(jsonPath("$.targets[0].uploadUrl").doesNotExist())
        .andExpect(jsonPath("$.targets[0].error").value(containsString("파일 이름이 너무 깁니다(최대 255바이트)")))
        .andExpect(jsonPath("$.targets[1].key").value("equip/report\\2024/ok.txt"))
        .andExpect(jsonPath("$.targets[1].uploadUrl").value(containsString("sig=put")))
        .andExpect(jsonPath("$.targets[1].error").doesNotExist());

    verify(storage, times(1)).presignedPutUrl(any(), any(), anyInt());
  }

  /**
   * WD-783: 루트에 올리는 255B 초과 이름(한글 300자 + ".txt")은 자르지 않고 그 파일만 error 로 싣는다 — 잘라 올리면 앞부분이 같은 이름끼리 같은
   * 키가 되어 서로 덮어쓴다. presign 은 하지 않는다.
   */
  @Test
  void createUploadUrls_rootRejectsFilenameOver255BytesWithoutTruncating() throws Exception {
    givenEquipConfig();
    givenPresignedPut();

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[{\"filename\":\"" + "가".repeat(300) + ".txt\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targets[0].key").doesNotExist())
        .andExpect(jsonPath("$.targets[0].error").value(containsString("255바이트")));

    verify(storage, times(0)).presignedPutUrl(any(), any(), anyInt());
  }

  /** WD-776: 폴더 경로의 세그먼트 하나가 255B 를 넘으면 그런 폴더는 MinIO 에 있을 수 없으므로 400 — presign 하지 않는다. */
  @Test
  void createUploadUrls_rejectsFolderSegmentOver255Bytes() throws Exception {
    givenEquipConfig();
    givenPresignedPut();

    mvc.perform(
            post("/api/v1/datasets/7/objects/upload-urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"ok/"
                        + "a".repeat(256)
                        + "\",\"files\":[{\"filename\":\"x.jpg\"}]}"))
        .andExpect(status().isBadRequest());

    verify(storage, never()).presignedPutUrl(any(), any(), anyInt());
  }

  /** 데이터셋 7 = 버킷 firehub-files, prefix equip/. */
  private void givenEquipConfig() {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
  }

  /** presigned PUT 은 키를 그대로 URL 에 담아 돌려준다(키 검증용). */
  private void givenPresignedPut() {
    when(storage.defaultUploadPresignExpiry()).thenReturn(900);
    when(storage.presignedPutUrl(eq("firehub-files"), any(), eq(900)))
        .thenAnswer(
            inv ->
                new PresignedUrlResponse("http://minio/" + inv.getArgument(1) + "?sig=put", 900));
  }
}

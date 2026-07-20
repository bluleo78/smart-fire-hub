package com.smartfirehub.file.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
              new FileObjectController(storage, configRepo, new ObjectKeyGenerator()))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void listObjects_usesDatasetConfigPrefix() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    when(storage.listObjects(eq("firehub-files"), eq("equip/"), any(), anyInt()))
        .thenReturn(
            new ObjectListResponse(
                List.of(new ObjectItemResponse("equip/a.jpg", 123L, "2026-07-20T00:00:00Z")),
                null,
                false));

    mvc.perform(get("/api/v1/datasets/7/objects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objects[0].key").value("equip/a.jpg"));
  }

  @Test
  void presignedUrl_delegatesWithConfiguredExpiry() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    // 컨트롤러가 하드코딩값이 아닌 storage.defaultPresignExpiry()를 사용해 위임하는지 검증한다.
    when(storage.defaultPresignExpiry()).thenReturn(900);
    when(storage.presignedGetUrl(eq("firehub-files"), eq("equip/a.jpg"), eq(900)))
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
        FileObjectController.class.getMethod("createUploadUrls", Long.class, UploadUrlRequest.class);
    RequirePermission ann = method.getAnnotation(RequirePermission.class);
    assertThat(ann).isNotNull();
    // RequirePermission.value()는 String[] — 단일 값 "dataset:write" 배열인지 확인한다.
    assertThat(ann.value()).containsExactly("dataset:write");
  }
}

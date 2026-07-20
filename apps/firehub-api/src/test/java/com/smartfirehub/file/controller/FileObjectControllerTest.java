package com.smartfirehub.file.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.file.dto.ObjectItemResponse;
import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.repository.FileDatasetConfigRepository.FileDatasetConfig;
import com.smartfirehub.file.service.FileObjectStorageService;
import com.smartfirehub.global.exception.GlobalExceptionHandler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
              new FileObjectController(storage, configRepo))
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
}

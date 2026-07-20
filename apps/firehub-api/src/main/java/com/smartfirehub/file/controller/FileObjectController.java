package com.smartfirehub.file.controller;

import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.repository.FileDatasetConfigRepository.FileDatasetConfig;
import com.smartfirehub.file.service.FileObjectStorageService;
import com.smartfirehub.global.security.RequirePermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FILE 데이터셋의 오브젝트 목록/서빙 엔드포인트. 앱은 바이트를 프록시하지 않고 목록과 presigned GET URL만 제공한다. */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/objects")
public class FileObjectController {

  private final FileObjectStorageService storage;
  private final FileDatasetConfigRepository configRepo;

  public FileObjectController(
      FileObjectStorageService storage, FileDatasetConfigRepository configRepo) {
    this.storage = storage;
    this.configRepo = configRepo;
  }

  /** 데이터셋 프리픽스 하위 오브젝트 목록(페이지네이션). */
  @GetMapping
  @RequirePermission("dataset:read")
  public ResponseEntity<ObjectListResponse> list(
      @PathVariable Long datasetId,
      @RequestParam(required = false) String token,
      @RequestParam(defaultValue = "50") int size) {
    FileDatasetConfig cfg = config(datasetId);
    // 악의적/실수로 지나치게 크거나 0 이하인 size 가 전달되어도 안전하도록 1~200 범위로 클램프한다.
    int cappedSize = Math.min(Math.max(size, 1), 200);
    return ResponseEntity.ok(storage.listObjects(cfg.bucket(), cfg.prefix(), token, cappedSize));
  }

  /** 오브젝트 단건 presigned GET URL. key는 프리픽스 포함 전체 키. */
  @GetMapping("/url")
  @RequirePermission("dataset:read")
  public ResponseEntity<PresignedUrlResponse> presignedUrl(
      @PathVariable Long datasetId, @RequestParam String key) {
    FileDatasetConfig cfg = config(datasetId);
    // 타 데이터셋 프리픽스로의 접근 차단(격리)
    if (!key.startsWith(cfg.prefix())) {
      throw new IllegalArgumentException("요청 키가 데이터셋 프리픽스에 속하지 않습니다");
    }
    // 하드코딩 만료값 대신 설정(firehub.minio.presign-expiry-seconds)을 사용한다.
    return ResponseEntity.ok(
        storage.presignedGetUrl(cfg.bucket(), key, storage.defaultPresignExpiry()));
  }

  /** 데이터셋의 FILE config 조회(없으면 FILE 데이터셋이 아님). */
  private FileDatasetConfig config(Long datasetId) {
    return configRepo
        .findByDatasetId(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("FILE 데이터셋이 아닙니다: " + datasetId));
  }
}

package com.smartfirehub.document.controller;

import com.smartfirehub.document.dto.DocumentFileResponse;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.document.service.DocumentIngestionService;
import com.smartfirehub.global.security.RequirePermission;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 문서 데이터셋의 파일 업로드/목록/상태/삭제 REST 엔드포인트. */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/documents")
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentIngestionService ingestionService;
  private final DocumentFileRepository fileRepository;

  /** 문서 업로드 — 동기 등록(중복검사+blob 저장+메타 생성) 후 비동기 인제스션을 enqueue하고 202를 반환한다. */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RequirePermission("data:import")
  public ResponseEntity<DocumentFileResponse> upload(
      @PathVariable Long datasetId,
      @RequestParam("file") MultipartFile file,
      Authentication authentication)
      throws Exception {
    // Authentication.getPrincipal()은 JwtAuthenticationFilter가 주입한 Long userId를 반환한다.
    Long userId = (Long) authentication.getPrincipal();
    DocumentFileResponse resp =
        ingestionService.upload(
            datasetId, file.getBytes(), file.getOriginalFilename(), file.getContentType(), userId);
    // 인제스션은 백그라운드 잡으로 진행되므로 202 Accepted로 응답한다.
    return ResponseEntity.accepted().body(resp);
  }

  /** 데이터셋의 문서 목록(최신순). */
  @GetMapping
  @RequirePermission("dataset:read")
  public List<DocumentFileResponse> list(@PathVariable Long datasetId) {
    return fileRepository.findByDataset(datasetId);
  }

  /** 단일 문서 상태/메타 조회. 없거나 다른 데이터셋 소속이면 404. */
  @GetMapping("/{documentId}")
  @RequirePermission("dataset:read")
  public ResponseEntity<DocumentFileResponse> get(
      @PathVariable Long datasetId, @PathVariable Long documentId) {
    // 경로의 datasetId로 소속을 검증해 다른 데이터셋의 문서가 노출되지 않도록 한다(교차 데이터셋 접근 차단).
    return fileRepository
        .findById(documentId)
        .filter(doc -> doc.datasetId().equals(datasetId))
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  /** 문서 삭제(document_chunk는 FK CASCADE, 원본 파일과 메타 정리). 없거나 다른 데이터셋 소속이면 404. */
  @DeleteMapping("/{documentId}")
  @RequirePermission("dataset:write")
  public ResponseEntity<Void> delete(
      @PathVariable Long datasetId, @PathVariable Long documentId) {
    // 경로의 datasetId로 소속을 검증해 다른 데이터셋의 문서를 삭제하지 못하게 한다(교차 데이터셋 삭제 차단).
    var doc = fileRepository.findById(documentId).filter(d -> d.datasetId().equals(datasetId));
    if (doc.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    ingestionService.deleteDocument(documentId);
    return ResponseEntity.noContent().build();
  }
}

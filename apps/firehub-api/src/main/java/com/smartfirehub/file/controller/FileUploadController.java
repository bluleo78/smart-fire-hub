package com.smartfirehub.file.controller;

import com.smartfirehub.file.dto.FileUploadResponse;
import com.smartfirehub.file.service.FileUploadService;
import com.smartfirehub.file.service.FileUploadService.FileContentResult;
import com.smartfirehub.global.security.RequirePermission;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileUploadController {

  private final FileUploadService fileUploadService;

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RequirePermission("ai:write")
  public ResponseEntity<List<FileUploadResponse>> uploadFiles(
      @RequestParam("files") List<MultipartFile> files, Authentication authentication)
      throws IOException {
    Long userId = (Long) authentication.getPrincipal();
    List<FileUploadResponse> responses = fileUploadService.uploadFiles(files, userId);
    return ResponseEntity.ok(responses);
  }

  @GetMapping("/{fileId}")
  @RequirePermission("ai:write")
  public ResponseEntity<FileUploadResponse> getFileInfo(
      @PathVariable Long fileId, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    FileUploadResponse response = fileUploadService.getFileInfo(fileId, userId);
    return ResponseEntity.ok(response);
  }

  /**
   * 파일 콘텐츠 다운로드. OOM 방지를 위해 byte[] 대신 Resource(스트리밍)로 응답한다. Spring의 ResourceHttpMessageConverter가
   * 청크 단위로 전송하므로 대용량 파일(최대 256MB)도 힙 메모리를 과소비하지 않는다.
   */
  @GetMapping("/{fileId}/content")
  public ResponseEntity<Resource> getFileContent(
      @PathVariable Long fileId, Authentication authentication) throws IOException {
    Long userId = (Long) authentication.getPrincipal();
    FileContentResult result = fileUploadService.getFileContent(fileId, userId);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(result.mimeType()));
    headers.setContentDisposition(
        ContentDisposition.inline()
            .filename(result.originalName(), StandardCharsets.UTF_8)
            .build());
    headers.setContentLength(result.size());

    return ResponseEntity.ok().headers(headers).body(result.resource());
  }
}

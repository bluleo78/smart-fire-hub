package com.smartfirehub.file.controller;

import com.smartfirehub.file.dto.FileUploadResponse;
import com.smartfirehub.file.service.FileUploadService;
import com.smartfirehub.file.service.FileUploadService.FileContentResult;
import com.smartfirehub.global.security.RequirePermission;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

  @GetMapping("/{fileId}/content")
  public ResponseEntity<byte[]> getFileContent(
      @PathVariable Long fileId, Authentication authentication) throws IOException {
    Long userId = (Long) authentication.getPrincipal();
    FileContentResult result = fileUploadService.getFileContent(fileId, userId);

    String encodedName =
        URLEncoder.encode(result.originalName(), StandardCharsets.UTF_8).replace("+", "%20");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(result.mimeType()));
    headers.setContentDisposition(
        ContentDisposition.inline()
            .filename(result.originalName(), StandardCharsets.UTF_8)
            .build());
    headers.setContentLength(result.content().length);

    return ResponseEntity.ok().headers(headers).body(result.content());
  }
}

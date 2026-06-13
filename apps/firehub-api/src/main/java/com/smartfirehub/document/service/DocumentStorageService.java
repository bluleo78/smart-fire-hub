package com.smartfirehub.document.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 문서 원본 blob을 영구 경로에 저장한다(uploaded_files 의 만료형과 분리). */
@Service
public class DocumentStorageService {

  private final String uploadDir;

  // FileUploadService 와 동일한 업로드 루트 키(firehub.file.upload-dir)를 사용해 저장 위치를 일치시킨다.
  public DocumentStorageService(@Value("${firehub.file.upload-dir:./uploads}") String uploadDir) {
    this.uploadDir = uploadDir;
  }

  /** 바이트를 documents/{date}/{uuid.ext} 로 저장하고 절대 경로 반환. */
  public String store(byte[] data, String originalName) {
    try {
      String ext = extension(originalName);
      String storedName = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
      String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
      Path dir = Paths.get(uploadDir, "documents", datePath).toAbsolutePath();
      Files.createDirectories(dir);
      Path target = dir.resolve(storedName);
      Files.write(target, data);
      return target.toString();
    } catch (Exception e) {
      throw new IllegalStateException("문서 저장 실패: " + originalName, e);
    }
  }

  /** 저장된 원본 blob을 바이트로 읽는다. */
  public byte[] read(String storagePath) {
    try {
      return Files.readAllBytes(Paths.get(storagePath));
    } catch (Exception e) {
      throw new IllegalStateException("문서 읽기 실패: " + storagePath, e);
    }
  }

  /** 저장된 원본 blob을 삭제한다(없어도 무시). */
  public void delete(String storagePath) {
    try {
      Files.deleteIfExists(Paths.get(storagePath));
    } catch (Exception e) {
      throw new IllegalStateException("문서 삭제 실패: " + storagePath, e);
    }
  }

  /** SHA-256 체크섬(중복 업로드 감지). */
  public String checksum(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(data));
    } catch (Exception e) {
      throw new IllegalStateException("체크섬 계산 실패", e);
    }
  }

  /** 파일명에서 확장자(소문자)를 추출한다. 없으면 빈 문자열. */
  private String extension(String name) {
    if (name == null) return "";
    int dot = name.lastIndexOf('.');
    // 소문자화 후 영숫자만 허용해 경로 조작/특수문자 주입을 차단한다.
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase().replaceAll("[^a-z0-9]", "");
  }
}

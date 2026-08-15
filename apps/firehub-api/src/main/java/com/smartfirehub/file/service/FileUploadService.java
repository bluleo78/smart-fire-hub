package com.smartfirehub.file.service;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;

import com.smartfirehub.file.dto.FileUploadResponse;
import com.smartfirehub.file.exception.FileNotFoundException;
import com.smartfirehub.file.exception.FileSizeLimitExceededException;
import com.smartfirehub.file.exception.UnsupportedUploadFileTypeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class FileUploadService {

  private static final Set<String> ALLOWED_MIME_TYPES =
      Set.of(
          "image/png",
          "image/jpeg",
          "image/gif",
          "image/webp",
          "application/pdf",
          "text/plain",
          "text/markdown",
          "application/json",
          "text/xml",
          "application/xml",
          "text/yaml",
          "application/x-yaml",
          "text/csv",
          "application/csv",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private static final Map<String, String> MIME_TO_CATEGORY =
      Map.ofEntries(
          Map.entry("image/png", "IMAGE"),
          Map.entry("image/jpeg", "IMAGE"),
          Map.entry("image/gif", "IMAGE"),
          Map.entry("image/webp", "IMAGE"),
          Map.entry("application/pdf", "PDF"),
          Map.entry("text/plain", "TEXT"),
          Map.entry("text/markdown", "TEXT"),
          Map.entry("application/json", "TEXT"),
          Map.entry("text/xml", "TEXT"),
          Map.entry("application/xml", "TEXT"),
          Map.entry("text/yaml", "TEXT"),
          Map.entry("application/x-yaml", "TEXT"),
          Map.entry("text/csv", "DATA"),
          Map.entry("application/csv", "DATA"),
          Map.entry(
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              "DOCUMENT"),
          Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "DATA"));

  // 카테고리별 첨부 사이즈 제한 (바이트). DATA는 대용량 CSV/XLSX 적재를 위해 256MB까지 허용,
  // 나머지(IMAGE/PDF/TEXT/DOCUMENT)는 10MB로 통일한다.
  private static final Map<String, Long> CATEGORY_SIZE_LIMITS =
      Map.of(
          "IMAGE", 10L * 1024 * 1024,
          "PDF", 10L * 1024 * 1024,
          "TEXT", 10L * 1024 * 1024,
          "DATA", 256L * 1024 * 1024,
          "DOCUMENT", 10L * 1024 * 1024);

  private final DSLContext dsl;
  private final TransactionTemplate transactionTemplate;
  private final String uploadDir;
  private final int maxFilesPerRequest;
  private final int expiryHours;

  public FileUploadService(
      DSLContext dsl,
      TransactionTemplate transactionTemplate,
      @Value("${firehub.file.upload-dir:./uploads}") String uploadDir,
      @Value("${firehub.file.max-files-per-request:3}") int maxFilesPerRequest,
      @Value("${firehub.file.expiry-hours:24}") int expiryHours) {
    this.dsl = dsl;
    this.transactionTemplate = transactionTemplate;
    this.uploadDir = uploadDir;
    this.maxFilesPerRequest = maxFilesPerRequest;
    this.expiryHours = expiryHours;
  }

  public List<FileUploadResponse> uploadFiles(List<MultipartFile> files, Long userId)
      throws IOException {
    if (files.size() > maxFilesPerRequest) {
      throw new IllegalArgumentException(
          "Too many files. Maximum " + maxFilesPerRequest + " files per request.");
    }

    List<FileUploadResponse> results = new java.util.ArrayList<>();
    for (MultipartFile file : files) {
      results.add(uploadSingleFile(file, userId));
    }
    return results;
  }

  private FileUploadResponse uploadSingleFile(MultipartFile file, Long userId) throws IOException {
    String mimeType = resolveMimeType(file);

    if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
      throw new UnsupportedUploadFileTypeException(mimeType);
    }

    String category = MIME_TO_CATEGORY.get(mimeType);
    long sizeLimit = CATEGORY_SIZE_LIMITS.get(category);
    if (file.getSize() > sizeLimit) {
      throw new FileSizeLimitExceededException(category, sizeLimit);
    }

    String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
    String ext = getExtension(originalName);
    String storedName = UUID.randomUUID().toString() + (ext.isEmpty() ? "" : "." + ext);

    String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    Path dir = Paths.get(uploadDir, "chat-files", datePath).toAbsolutePath();
    Files.createDirectories(dir);

    Path storagePath = dir.resolve(storedName);
    file.transferTo(storagePath.toFile());

    Instant now = Instant.now();
    Instant expiresAt = now.plusSeconds((long) expiryHours * 3600);

    // 디스크 쓰기(file.transferTo, 위)는 이미 끝났다 — 여기서부터 DB 삽입 구간만 TransactionTemplate 으로
    // 감싸 GUC(app.tenant_id)가 주입된 트랜잭션 커넥션으로 insert 하되, 파일시스템 I/O 는 트랜잭션
    // 경계 밖에 남긴다(느린 디스크 I/O 가 커넥션을 점유하는 것을 방지, 데이터임포트 서비스와 동일 패턴).
    Long fileId =
        transactionTemplate.execute(
            status ->
                dsl.insertInto(UPLOADED_FILES)
                    .set(UPLOADED_FILES.ORIGINAL_NAME, originalName)
                    .set(UPLOADED_FILES.STORED_NAME, storedName)
                    .set(UPLOADED_FILES.MIME_TYPE, mimeType)
                    .set(UPLOADED_FILES.FILE_SIZE, file.getSize())
                    .set(UPLOADED_FILES.FILE_CATEGORY, category)
                    .set(UPLOADED_FILES.STORAGE_PATH, storagePath.toAbsolutePath().toString())
                    .set(UPLOADED_FILES.UPLOADED_BY, userId)
                    .set(UPLOADED_FILES.CREATED_AT, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    .set(
                        UPLOADED_FILES.EXPIRES_AT, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                    .returning(UPLOADED_FILES.ID)
                    .fetchOne()
                    .getId());

    log.info(
        "File uploaded: id={}, name={}, category={}, size={}",
        fileId,
        originalName,
        category,
        file.getSize());

    return new FileUploadResponse(fileId, originalName, mimeType, file.getSize(), category, now);
  }

  // dsl 직접 사용(리포지토리 미경유) — @Transactional 이 없으면 GUC 미주입으로 RLS 가 전 행을 가려
  // 예외 없이 fetchOne()==null → FileNotFoundException 으로 오인된다. 실결함 수정.
  @Transactional(readOnly = true)
  public FileUploadResponse getFileInfo(Long fileId, Long userId) {
    var record =
        dsl.selectFrom(UPLOADED_FILES)
            .where(UPLOADED_FILES.ID.eq(fileId))
            .and(UPLOADED_FILES.UPLOADED_BY.eq(userId))
            .fetchOne();

    if (record == null) {
      throw new FileNotFoundException(fileId);
    }

    return new FileUploadResponse(
        record.getId(),
        record.getOriginalName(),
        record.getMimeType(),
        record.getFileSize(),
        record.getFileCategory(),
        record.getCreatedAt().toInstant());
  }

  /** 파일 다운로드 결과 레코드. byte[] 대신 Resource를 사용하여 대용량 파일(최대 256MB)을 스트리밍으로 전송하고 힙 메모리 과소비를 방지한다. */
  public record FileContentResult(
      Resource resource, String mimeType, String originalName, long size) {}

  // dsl 직접 사용 — 실결함 수정. 아래 Files.exists/Files.size 는 메타데이터 확인만 하는 짧은 로컬 호출로,
  // 실제 파일 스트리밍(FileSystemResource 소비)은 컨트롤러 응답 단계에서 트랜잭션 밖에 일어난다.
  @Transactional(readOnly = true)
  public FileContentResult getFileContent(Long fileId, Long userId) throws IOException {
    var record =
        dsl.selectFrom(UPLOADED_FILES)
            .where(UPLOADED_FILES.ID.eq(fileId))
            .and(UPLOADED_FILES.UPLOADED_BY.eq(userId))
            .fetchOne();

    if (record == null) {
      throw new FileNotFoundException(fileId);
    }

    Path storagePath = Path.of(record.getStoragePath());
    if (!Files.exists(storagePath)) {
      throw new FileNotFoundException(fileId);
    }

    // Files.readAllBytes() 대신 FileSystemResource로 스트리밍 반환.
    // DATA 카테고리 파일은 최대 256MB이므로 전체 로드 시 OOM 위험 → 스트리밍 방식 필수.
    long fileSize = Files.size(storagePath);
    Resource resource = new FileSystemResource(storagePath);
    return new FileContentResult(
        resource, record.getMimeType(), record.getOriginalName(), fileSize);
  }

  private String resolveMimeType(MultipartFile file) {
    String contentType = file.getContentType();
    if (contentType != null
        && !contentType.isBlank()
        && !contentType.equals("application/octet-stream")) {
      return contentType.split(";")[0].trim().toLowerCase();
    }
    // Fallback: derive from filename extension
    String name = file.getOriginalFilename();
    if (name != null) {
      String ext = getExtension(name).toLowerCase();
      return switch (ext) {
        case "png" -> "image/png";
        case "jpg", "jpeg" -> "image/jpeg";
        case "gif" -> "image/gif";
        case "webp" -> "image/webp";
        case "pdf" -> "application/pdf";
        case "txt" -> "text/plain";
        case "md" -> "text/markdown";
        case "json" -> "application/json";
        case "xml" -> "text/xml";
        case "yaml", "yml" -> "text/yaml";
        case "csv" -> "text/csv";
        case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        default -> contentType != null ? contentType : "application/octet-stream";
      };
    }
    return contentType != null ? contentType : "application/octet-stream";
  }

  private String getExtension(String filename) {
    int dotIdx = filename.lastIndexOf('.');
    return dotIdx >= 0 ? filename.substring(dotIdx + 1) : "";
  }
}

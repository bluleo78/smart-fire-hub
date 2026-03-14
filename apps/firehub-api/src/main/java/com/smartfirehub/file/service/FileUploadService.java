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
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileUploadService {

  private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

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
          "application/csv");

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
          Map.entry("application/csv", "DATA"));

  // Category size limits in bytes
  private static final Map<String, Long> CATEGORY_SIZE_LIMITS =
      Map.of(
          "IMAGE", 5L * 1024 * 1024,
          "PDF", 10L * 1024 * 1024,
          "TEXT", 1L * 1024 * 1024,
          "DATA", 5L * 1024 * 1024);

  private final DSLContext dsl;
  private final String uploadDir;
  private final int maxFilesPerRequest;
  private final int expiryHours;

  public FileUploadService(
      DSLContext dsl,
      @Value("${firehub.file.upload-dir:./uploads}") String uploadDir,
      @Value("${firehub.file.max-files-per-request:3}") int maxFilesPerRequest,
      @Value("${firehub.file.expiry-hours:24}") int expiryHours) {
    this.dsl = dsl;
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

    Long fileId =
        dsl.insertInto(UPLOADED_FILES)
            .set(UPLOADED_FILES.ORIGINAL_NAME, originalName)
            .set(UPLOADED_FILES.STORED_NAME, storedName)
            .set(UPLOADED_FILES.MIME_TYPE, mimeType)
            .set(UPLOADED_FILES.FILE_SIZE, file.getSize())
            .set(UPLOADED_FILES.FILE_CATEGORY, category)
            .set(UPLOADED_FILES.STORAGE_PATH, storagePath.toAbsolutePath().toString())
            .set(UPLOADED_FILES.UPLOADED_BY, userId)
            .set(UPLOADED_FILES.CREATED_AT, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .set(UPLOADED_FILES.EXPIRES_AT, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
            .returning(UPLOADED_FILES.ID)
            .fetchOne()
            .getId();

    log.info(
        "File uploaded: id={}, name={}, category={}, size={}",
        fileId,
        originalName,
        category,
        file.getSize());

    return new FileUploadResponse(fileId, originalName, mimeType, file.getSize(), category, now);
  }

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

  public record FileContentResult(byte[] content, String mimeType, String originalName) {}

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

    byte[] content = Files.readAllBytes(storagePath);
    return new FileContentResult(content, record.getMimeType(), record.getOriginalName());
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

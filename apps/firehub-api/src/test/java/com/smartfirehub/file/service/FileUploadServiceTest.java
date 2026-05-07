package com.smartfirehub.file.service;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.file.dto.FileUploadResponse;
import com.smartfirehub.file.exception.FileNotFoundException;
import com.smartfirehub.file.exception.FileSizeLimitExceededException;
import com.smartfirehub.file.exception.UnsupportedUploadFileTypeException;
import com.smartfirehub.file.service.FileUploadService.FileContentResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class FileUploadServiceTest extends IntegrationTestBase {

  @Autowired private FileUploadService fileUploadService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long otherUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "filetest_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "File Test User")
            .set(USER.EMAIL, "filetest@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    otherUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "other_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Other User")
            .set(USER.EMAIL, "other@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  @Test
  void uploadFiles_imageFile_success() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("files", "test.png", "image/png", "fake-png-content".getBytes());

    List<FileUploadResponse> responses = fileUploadService.uploadFiles(List.of(file), testUserId);

    assertThat(responses).hasSize(1);
    FileUploadResponse response = responses.get(0);
    assertThat(response.id()).isNotNull();
    assertThat(response.originalName()).isEqualTo("test.png");
    assertThat(response.mimeType()).isEqualTo("image/png");
    assertThat(response.fileCategory()).isEqualTo("IMAGE");
    assertThat(response.fileSize()).isEqualTo("fake-png-content".length());
  }

  @Test
  void uploadFiles_pdfFile_success() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("files", "doc.pdf", "application/pdf", "fake-pdf-content".getBytes());

    List<FileUploadResponse> responses = fileUploadService.uploadFiles(List.of(file), testUserId);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).fileCategory()).isEqualTo("PDF");
  }

  @Test
  void uploadFiles_csvFile_success() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("files", "data.csv", "text/csv", "a,b,c\n1,2,3".getBytes());

    List<FileUploadResponse> responses = fileUploadService.uploadFiles(List.of(file), testUserId);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).fileCategory()).isEqualTo("DATA");
  }

  @Test
  void uploadFiles_textFile_success() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("files", "note.txt", "text/plain", "hello world".getBytes());

    List<FileUploadResponse> responses = fileUploadService.uploadFiles(List.of(file), testUserId);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).fileCategory()).isEqualTo("TEXT");
  }

  @Test
  void uploadFiles_unsupportedType_throwsException() {
    MockMultipartFile file =
        new MockMultipartFile(
            "files", "virus.exe", "application/x-msdownload", "fake-exe".getBytes());

    assertThatThrownBy(() -> fileUploadService.uploadFiles(List.of(file), testUserId))
        .isInstanceOf(UnsupportedUploadFileTypeException.class);
  }

  @Test
  void uploadFiles_imageTooLarge_throwsException() {
    // IMAGE 한도 10MB 초과 케이스로 11MB 사용
    byte[] bigContent = new byte[11 * 1024 * 1024];
    MockMultipartFile file = new MockMultipartFile("files", "big.png", "image/png", bigContent);

    assertThatThrownBy(() -> fileUploadService.uploadFiles(List.of(file), testUserId))
        .isInstanceOf(FileSizeLimitExceededException.class)
        .hasMessageContaining("IMAGE");
  }

  @Test
  void uploadFiles_textTooLarge_throwsException() {
    // TEXT 한도 10MB 초과 케이스로 11MB 사용
    byte[] bigContent = new byte[11 * 1024 * 1024];
    MockMultipartFile file = new MockMultipartFile("files", "big.txt", "text/plain", bigContent);

    assertThatThrownBy(() -> fileUploadService.uploadFiles(List.of(file), testUserId))
        .isInstanceOf(FileSizeLimitExceededException.class)
        .hasMessageContaining("TEXT");
  }

  @Test
  void uploadFiles_tooManyFiles_throwsException() {
    MockMultipartFile f1 = new MockMultipartFile("files", "a.txt", "text/plain", "a".getBytes());
    MockMultipartFile f2 = new MockMultipartFile("files", "b.txt", "text/plain", "b".getBytes());
    MockMultipartFile f3 = new MockMultipartFile("files", "c.txt", "text/plain", "c".getBytes());
    MockMultipartFile f4 = new MockMultipartFile("files", "d.txt", "text/plain", "d".getBytes());

    assertThatThrownBy(() -> fileUploadService.uploadFiles(List.of(f1, f2, f3, f4), testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Too many files");
  }

  @Test
  void getFileInfo_ownFile_success() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("files", "info.txt", "text/plain", "content".getBytes());
    List<FileUploadResponse> uploaded = fileUploadService.uploadFiles(List.of(file), testUserId);
    Long fileId = uploaded.get(0).id();

    FileUploadResponse info = fileUploadService.getFileInfo(fileId, testUserId);

    assertThat(info.id()).isEqualTo(fileId);
    assertThat(info.originalName()).isEqualTo("info.txt");
  }

  @Test
  void getFileInfo_otherUsersFile_throwsNotFound() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("files", "secret.txt", "text/plain", "secret".getBytes());
    List<FileUploadResponse> uploaded = fileUploadService.uploadFiles(List.of(file), testUserId);
    Long fileId = uploaded.get(0).id();

    assertThatThrownBy(() -> fileUploadService.getFileInfo(fileId, otherUserId))
        .isInstanceOf(FileNotFoundException.class);
  }

  /**
   * getFileContent()가 byte[] 대신 Resource를 반환하는지 검증.
   * OOM 방지를 위해 스트리밍 방식(FileSystemResource)으로 파일을 제공하므로
   * resource.getInputStream()으로 읽어 내용을 확인한다.
   */
  @Test
  void getFileContent_ownFile_returnsStreamableResource() throws IOException {
    byte[] fileBytes = "hello file content".getBytes();
    MockMultipartFile file = new MockMultipartFile("files", "content.txt", "text/plain", fileBytes);
    List<FileUploadResponse> uploaded = fileUploadService.uploadFiles(List.of(file), testUserId);
    Long fileId = uploaded.get(0).id();

    FileContentResult result = fileUploadService.getFileContent(fileId, testUserId);

    // byte[] 필드가 없고 Resource 필드로 스트리밍됨을 확인
    assertThat(result.resource()).isNotNull();
    assertThat(result.mimeType()).isEqualTo("text/plain");
    assertThat(result.originalName()).isEqualTo("content.txt");
    assertThat(result.size()).isEqualTo(fileBytes.length);

    // InputStream으로 읽어 실제 내용 동일성 검증
    try (InputStream is = result.resource().getInputStream()) {
      byte[] actual = is.readAllBytes();
      assertThat(actual).isEqualTo(fileBytes);
    }
  }

  /**
   * 대용량 파일(스트리밍 대상)에서도 Resource가 올바르게 반환되는지 검증.
   * 실제 OOM은 재현 불가이므로 구조적 검증(byte[] 미사용)으로 대체한다.
   */
  @Test
  void getFileContent_largeFile_returnsResourceWithCorrectSize() throws IOException {
    // 5MB 파일로 size 필드 정확성 검증
    byte[] largeBytes = new byte[5 * 1024 * 1024];
    new java.util.Random().nextBytes(largeBytes);
    MockMultipartFile file = new MockMultipartFile("files", "large.csv", "text/csv", largeBytes);
    List<FileUploadResponse> uploaded = fileUploadService.uploadFiles(List.of(file), testUserId);
    Long fileId = uploaded.get(0).id();

    FileContentResult result = fileUploadService.getFileContent(fileId, testUserId);

    assertThat(result.resource()).isNotNull();
    assertThat(result.size()).isEqualTo(largeBytes.length);
    assertThat(result.mimeType()).isEqualTo("text/csv");
  }

  @Test
  void getFileContent_otherUsersFile_throwsNotFound() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("files", "mine.txt", "text/plain", "content".getBytes());
    List<FileUploadResponse> uploaded = fileUploadService.uploadFiles(List.of(file), testUserId);
    Long fileId = uploaded.get(0).id();

    assertThatThrownBy(() -> fileUploadService.getFileContent(fileId, otherUserId))
        .isInstanceOf(FileNotFoundException.class);
  }

  @AfterEach
  void cleanupUploadedFilesFromDisk() {
    // Clean up any physical files written during tests
    List<String> paths =
        dsl.select(UPLOADED_FILES.STORAGE_PATH)
            .from(UPLOADED_FILES)
            .where(UPLOADED_FILES.UPLOADED_BY.eq(testUserId))
            .fetchInto(String.class);
    for (String p : paths) {
      try {
        Files.deleteIfExists(Path.of(p));
      } catch (IOException ignored) {
      }
    }
  }
}

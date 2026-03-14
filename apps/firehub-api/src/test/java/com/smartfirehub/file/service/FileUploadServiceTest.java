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
    // IMAGE limit is 5MB; create 6MB content
    byte[] bigContent = new byte[6 * 1024 * 1024];
    MockMultipartFile file = new MockMultipartFile("files", "big.png", "image/png", bigContent);

    assertThatThrownBy(() -> fileUploadService.uploadFiles(List.of(file), testUserId))
        .isInstanceOf(FileSizeLimitExceededException.class)
        .hasMessageContaining("IMAGE");
  }

  @Test
  void uploadFiles_textTooLarge_throwsException() {
    // TEXT limit is 1MB; create 2MB content
    byte[] bigContent = new byte[2 * 1024 * 1024];
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

  @Test
  void getFileContent_ownFile_returnsBytes() throws IOException {
    byte[] fileBytes = "hello file content".getBytes();
    MockMultipartFile file = new MockMultipartFile("files", "content.txt", "text/plain", fileBytes);
    List<FileUploadResponse> uploaded = fileUploadService.uploadFiles(List.of(file), testUserId);
    Long fileId = uploaded.get(0).id();

    FileContentResult result = fileUploadService.getFileContent(fileId, testUserId);

    assertThat(result.content()).isEqualTo(fileBytes);
    assertThat(result.mimeType()).isEqualTo("text/plain");
    assertThat(result.originalName()).isEqualTo("content.txt");
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

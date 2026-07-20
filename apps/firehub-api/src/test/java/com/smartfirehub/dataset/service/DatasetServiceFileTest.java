package com.smartfirehub.dataset.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetCategoryRepository;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetTagRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingService;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.service.FileObjectStorageService;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * FILE 데이터셋 생성 시 물리 테이블/컬럼을 만들지 않고 file_dataset_config 만 저장하는지 검증하는 순수 Mockito 단위 테스트.
 *
 * <p>DatasetService 는 다수 의존성을 갖는 대형 서비스이므로 {@code @InjectMocks} 로 {@code @RequiredArgsConstructor}
 * 생성자를 자동 주입한다. createDataset 은 성공 시 내부적으로 getDatasetById 를 호출해 응답을 재구성하므로, 그 경로가 예외 없이 끝까지 흐르도록
 * datasetRepository.findById / dsl(deep stub) 도 함께 스텁한다.
 */
@ExtendWith(MockitoExtension.class)
class DatasetServiceFileTest {

  @Mock private DatasetRepository datasetRepository;
  @Mock private DatasetColumnRepository columnRepository;
  @Mock private DatasetCategoryRepository categoryRepository;
  @Mock private DataTableService dataTableService;
  @Mock private DataTableRowService dataTableRowService;
  @Mock private UserRepository userRepository;
  @Mock private DatasetTagRepository tagRepository;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private DSLContext dsl;

  @Mock private AuditLogService auditLogService;
  @Mock private DatasetEmbeddingService datasetEmbeddingService;
  @Mock private ApplicationEventPublisher events;
  @Mock private FileDatasetConfigRepository fileDatasetConfigRepository;
  @Mock private FileObjectStorageService fileObjectStorageService;

  private DatasetService datasetService;

  private static final Long DATASET_ID = 100L;
  private static final Long USER_ID = 1L;

  @BeforeEach
  void setUp() {
    // DatasetService 는 @RequiredArgsConstructor 로 생성되므로 실제 생성자 시그니처(필드 선언 순서)와
    // 동일한 순서로 직접 생성한다.
    datasetService =
        new DatasetService(
            datasetRepository,
            columnRepository,
            categoryRepository,
            dataTableService,
            dataTableRowService,
            userRepository,
            tagRepository,
            dsl,
            auditLogService,
            datasetEmbeddingService,
            events,
            fileDatasetConfigRepository,
            fileObjectStorageService);
  }

  @Test
  void createFileDataset_skipsTableCreation_andSavesConfig() {
    // given: storageType=FILE 요청 (bucket/prefix 미지정 → 기본 버킷 + 데이터셋별 격리 프리픽스로 저장되어야 함)
    CreateDatasetRequest request =
        new CreateDatasetRequest(
            "File Dataset",
            "file_dataset",
            "FILE storage dataset",
            null,
            "FILE",
            "SOURCE",
            List.of(),
            null,
            null,
            null);

    DatasetResponse savedDataset =
        new DatasetResponse(
            DATASET_ID,
            "File Dataset",
            "file_dataset",
            "FILE storage dataset",
            null,
            "FILE",
            "SOURCE",
            null,
            false,
            List.of(),
            "NONE",
            null,
            null,
            null,
            null);

    when(datasetRepository.save(request, USER_ID)).thenReturn(savedDataset);
    // createDataset 성공 후 getDatasetById(dataset.id()) 를 호출하므로 조회 경로도 스텁해야 한다.
    when(datasetRepository.findById(eq(DATASET_ID), any())).thenReturn(Optional.of(savedDataset));
    when(fileObjectStorageService.defaultBucket()).thenReturn("default-bucket");

    // when
    DatasetDetailResponse response = datasetService.createDataset(request, USER_ID);

    // then: 물리 테이블/컬럼 미생성
    verify(dataTableService, never()).createTable(anyString(), any());
    verify(columnRepository, never()).saveBatch(anyLong(), any());
    // then: file_dataset_config 저장 (기본 버킷 사용, 빈 프리픽스 대신 "datasets/<id>/" 격리 프리픽스)
    // 빈 프리픽스("")는 컨트롤러의 key.startsWith(prefix) 격리를 무력화하므로 반드시 고유 프리픽스여야 한다.
    verify(fileDatasetConfigRepository).save(eq(DATASET_ID), anyString(), anyString());
    verify(fileDatasetConfigRepository)
        .save(DATASET_ID, "default-bucket", "datasets/" + DATASET_ID + "/");

    org.assertj.core.api.Assertions.assertThat(response.storageType()).isEqualTo("FILE");
  }

  @Test
  void createFileDataset_withExplicitBucketAndPrefix_normalizesTrailingSlash() {
    // given: 요청에 버킷/프리픽스를 명시적으로 지정 ("equip" → 저장 시 "equip/" 로 trailing slash 정규화되어야 함).
    // 이 케이스는 request 가 지정한 bucket/prefix 가 그대로(정규화만 거쳐) 전달되는지도 함께 검증한다.
    CreateDatasetRequest request =
        new CreateDatasetRequest(
            "Equip File Dataset",
            "equip_file_dataset",
            "FILE storage dataset with explicit bucket/prefix",
            null,
            "FILE",
            "SOURCE",
            List.of(),
            null,
            "custom-bucket",
            "equip");

    DatasetResponse savedDataset =
        new DatasetResponse(
            DATASET_ID,
            "Equip File Dataset",
            "equip_file_dataset",
            "FILE storage dataset with explicit bucket/prefix",
            null,
            "FILE",
            "SOURCE",
            null,
            false,
            List.of(),
            "NONE",
            null,
            null,
            null,
            null);

    when(datasetRepository.save(request, USER_ID)).thenReturn(savedDataset);
    when(datasetRepository.findById(eq(DATASET_ID), any())).thenReturn(Optional.of(savedDataset));

    // when
    datasetService.createDataset(request, USER_ID);

    // then: request 가 지정한 bucket 이 그대로 전달되고(fileObjectStorageService.defaultBucket() 미호출),
    // prefix 는 "equip" → "equip/" 로 trailing slash 가 붙어 "equipment/..." 와 부분 일치하지 않는다.
    verify(fileObjectStorageService, never()).defaultBucket();
    verify(fileDatasetConfigRepository).save(DATASET_ID, "custom-bucket", "equip/");
  }

  @Test
  void createFileDataset_withNullColumns_doesNotThrow_andSavesConfig() {
    // given: columns == null 인 FILE 요청 (d2a4f266 에서 고친 NPE 회귀 방지용).
    // createDataset 은 storageType 분기 이전에 request.columns() 를 순회했었는데,
    // FILE 요청은 columns 가 null 로 넘어올 수 있어 NPE 가 발생했다.
    // 이 테스트는 columns != null ? ... : List.of() 정규화 가드가 되돌려지면 실패해야 한다.
    CreateDatasetRequest request =
        new CreateDatasetRequest(
            "Null Columns File Dataset",
            "null_columns_file_dataset",
            "FILE storage dataset with null columns",
            null,
            "FILE",
            "SOURCE",
            null,
            null,
            null,
            null);

    DatasetResponse savedDataset =
        new DatasetResponse(
            DATASET_ID,
            "Null Columns File Dataset",
            "null_columns_file_dataset",
            "FILE storage dataset with null columns",
            null,
            "FILE",
            "SOURCE",
            null,
            false,
            List.of(),
            "NONE",
            null,
            null,
            null,
            null);

    when(datasetRepository.save(request, USER_ID)).thenReturn(savedDataset);
    when(datasetRepository.findById(eq(DATASET_ID), any())).thenReturn(Optional.of(savedDataset));
    when(fileObjectStorageService.defaultBucket()).thenReturn("default-bucket");

    // when: columns 가 null 이어도 예외 없이 생성되어야 한다.
    DatasetDetailResponse response = datasetService.createDataset(request, USER_ID);

    // then: 물리 테이블/컬럼 미생성 + file_dataset_config 저장
    verify(dataTableService, never()).createTable(anyString(), any());
    verify(columnRepository, never()).saveBatch(anyLong(), any());
    verify(fileDatasetConfigRepository)
        .save(DATASET_ID, "default-bucket", "datasets/" + DATASET_ID + "/");

    org.assertj.core.api.Assertions.assertThat(response.storageType()).isEqualTo("FILE");
  }
}

package com.smartfirehub.file.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.file.repository.FileDatasetConfigRepository.FileDatasetConfig;
import com.smartfirehub.user.repository.TenantPairContractBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FileDatasetConfigRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-712). file_dataset_config 는 테넌트 정책 아래
 * 있다.
 */
public abstract class FileDatasetConfigRepositoryContractTest extends TenantPairContractBase {

  protected abstract FileDatasetConfigRepository repository();

  @Test
  @DisplayName("save 한 매핑이 그대로 조회되고 없는 데이터셋은 비어 있다")
  void save_roundTrip() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    runA(() -> repository().save(ds, "한글-bucket", "files/" + prefix + "/"));

    FileDatasetConfig c = inA(() -> repository().findByDatasetId(ds)).orElseThrow();

    assertThat(c.datasetId()).isEqualTo(ds);
    assertThat(c.bucket()).isEqualTo("한글-bucket");
    assertThat(c.prefix()).isEqualTo("files/" + prefix + "/");
    assertThat(fx.countTenantRows("file_dataset_config", tenantA)).isEqualTo(1);
    assertThat(inA(() -> repository().findByDatasetId(-1L))).isEmpty();
  }

  @Test
  @DisplayName("같은 데이터셋에 두 번 저장하면 유일 키 위반이고 기존 값은 그대로다")
  void save_duplicateRejected() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    runA(() -> repository().save(ds, "b1", "p1/"));

    assertThatThrownBy(() -> runA(() -> repository().save(ds, "b2", "p2/")))
        .isInstanceOf(RuntimeException.class);

    assertThat(inA(() -> repository().findByDatasetId(ds)).orElseThrow().bucket()).isEqualTo("b1");
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 A 데이터셋의 매핑이 보이지 않는다")
  void otherTenantIsBlocked() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    runA(() -> repository().save(ds, "b1", "p1/"));

    assertThat(inB(() -> repository().findByDatasetId(ds))).isEmpty();
  }
}

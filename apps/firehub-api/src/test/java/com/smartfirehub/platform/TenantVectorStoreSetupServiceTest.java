package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.platform.dto.VectorStoreSetupRequest;
import com.smartfirehub.platform.service.TenantVectorStoreSetupService;
import com.smartfirehub.vectorstore.tenant.DedicatedVectorStoreProbe;
import com.smartfirehub.vectorstore.tenant.TenantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreRepository;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreTargets;
import com.smartfirehub.vectorstore.tenant.VectorStoreConnectionTestResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 테넌트 전용 벡터 저장소 설정 서비스(WD-742) — Qdrant 조합에서만 받고, 키는 암호화해 저장하며, 응답에 비밀을 싣지 않는다. */
class TenantVectorStoreSetupServiceTest {

  private final TenantVectorStoreRepository repository = mock(TenantVectorStoreRepository.class);
  private final EncryptionService encryption = mock(EncryptionService.class);
  private final DedicatedVectorStoreProbe probe = mock(DedicatedVectorStoreProbe.class);
  private final TenantVectorStoreTargets targets = mock(TenantVectorStoreTargets.class);
  private final VectorStoreSetupRequest request =
      new VectorStoreSetupRequest("qdrant.cust", 6334, true, "secret");

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T bean) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(bean);
    return p;
  }

  private TenantVectorStoreSetupService service(DedicatedVectorStoreProbe p) {
    return new TenantVectorStoreSetupService(
        repository, encryption, provider(p), provider(targets));
  }

  @Test
  void qdrant_조합이_아니면_입력을_거절하고_입력이_없으면_통과한다() {
    TenantVectorStoreSetupService s = service(null);
    assertThat(s.dedicatedSupported()).isFalse();
    s.validate(null);
    assertThatThrownBy(() -> s.validate(request)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> s.testConnection(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 저장은_키를_암호화하고_감사에는_주소만_싣고_캐시를_비운다() {
    when(encryption.encrypt("secret")).thenReturn("enc");
    var audit = service(probe).setUp(7L, request);

    verify(repository).insert(new TenantVectorStore(7L, "qdrant.cust", 6334, true, "enc"));
    assertThat(audit).containsEntry("vectorStoreHost", "qdrant.cust").doesNotContainValue("secret");
    // 트랜잭션 밖 호출이라 바로 비운다(트랜잭션 안이면 커밋 뒤).
    verify(targets).invalidate(7L);
  }

  @Test
  void 입력이_없으면_저장하지_않는다() {
    assertThat(service(probe).setUp(7L, null)).isEmpty();
    verify(repository, never()).insert(any());
  }

  @Test
  void 연결_확인은_probe_에_맡기고_상세에는_비밀이_없다() {
    when(probe.probe("qdrant.cust", 6334, true, "secret"))
        .thenReturn(new VectorStoreConnectionTestResult(true, "1.20.0", "연결됨"));
    assertThat(service(probe).testConnection(request).ok()).isTrue();

    when(repository.find(7L))
        .thenReturn(Optional.of(new TenantVectorStore(7L, "qdrant.cust", 6334, true, "enc")));
    var detail = service(probe).detail(7L);
    assertThat(detail.dedicated()).isTrue();
    assertThat(detail.host()).isEqualTo("qdrant.cust");
    assertThat(request.toString()).doesNotContain("secret");
    when(repository.find(8L)).thenReturn(Optional.empty());
    assertThat(service(probe).detail(8L).dedicated()).isFalse();
  }
}

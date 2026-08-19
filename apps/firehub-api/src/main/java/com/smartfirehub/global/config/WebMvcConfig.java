package com.smartfirehub.global.config;

import com.smartfirehub.global.security.PermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

  private final PermissionInterceptor permissionInterceptor;

  /**
   * 권한 인터셉터 등록.
   *
   * <p><b>운영자 평면({@code /api/platform/**})을 반드시 함께 등록해야 한다.</b> 이 목록에서 빠지면
   * 그 경로의 {@code @RequirePermission} 이 <b>조용히 무시</b>되어, 어노테이션은 붙어 있는데 권한
   * 검사는 전혀 일어나지 않는 상태가 된다 — 실제로 P7-a 에서 그 상태를 만들었고
   * {@code PlatformTenantControllerTest.withoutPlatformPermissions_isForbidden} 이 잡았다.
   * 컴파일도 기동도 통과하므로 테스트 없이는 드러나지 않는다.
   */
  @Override
  public void addInterceptors(@NonNull InterceptorRegistry registry) {
    registry
        .addInterceptor(permissionInterceptor)
        .addPathPatterns("/api/v1/**", "/api/platform/**");
  }

  @Override
  public void configureAsyncSupport(@NonNull AsyncSupportConfigurer configurer) {
    configurer.setDefaultTimeout(300_000L);
  }
}

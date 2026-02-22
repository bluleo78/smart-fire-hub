package com.smartfirehub.global.config;

import com.smartfirehub.global.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final PermissionInterceptor permissionInterceptor;

  public WebMvcConfig(PermissionInterceptor permissionInterceptor) {
    this.permissionInterceptor = permissionInterceptor;
  }

  @Override
  public void addInterceptors(@NonNull InterceptorRegistry registry) {
    registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/v1/**");
  }

  @Override
  public void configureAsyncSupport(@NonNull AsyncSupportConfigurer configurer) {
    configurer.setDefaultTimeout(300_000L);
  }
}

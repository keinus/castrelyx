package org.castrelyx.manager.config;

import org.castrelyx.manager.auth.RbacInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final RbacInterceptor rbacInterceptor;

  public WebConfig(RbacInterceptor rbacInterceptor) {
    this.rbacInterceptor = rbacInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(rbacInterceptor).addPathPatterns("/api/**");
  }
}

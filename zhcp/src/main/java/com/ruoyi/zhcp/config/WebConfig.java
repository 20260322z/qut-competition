package com.ruoyi.zhcp.config;

import com.ruoyi.zhcp.common.AjaxResult;
import com.ruoyi.zhcp.common.ServiceException;
import com.ruoyi.zhcp.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/login", "/app/login", "/health", "/error", "/favicon.ico");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOriginPatterns("*")
                .allowedMethods("*").allowedHeaders("*").allowCredentials(true);
    }

    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(ServiceException.class)
        public ResponseEntity<AjaxResult> handle(ServiceException e) {
            return ResponseEntity.ok(AjaxResult.error(e.getCode(), e.getMessage()));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<AjaxResult> other(Exception e) {
            return ResponseEntity.ok(AjaxResult.error(e.getMessage() == null ? "服务异常" : e.getMessage()));
        }
    }
}

package com.shiyi.trigger.config;

import com.shiyi.trigger.http.interceptor.ApiKeyInterceptor;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册鉴权拦截器。
 * <p>
 * 只拦 {@code /api/**}，其它路径（静态资源、错误页、actuator）不动，
 * 避免把 Spring 内部的错误转发也挡掉、导致真实错误被 401 盖住。
 *
 * @author shiyi
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private ApiKeyInterceptor apiKeyInterceptor;

    /**
     * 允许跨域的来源。默认只放开本机开发端口与 Tauri WebView，
     * 不再使用 {@code origins = "*"}（那等于允许任意网页携带用户浏览器环境调用本服务）。
     */
    @Value("${app.security.cors-allowed-origins:http://localhost:5173,http://127.0.0.1:5173,tauri://localhost,http://tauri.localhost}")
    private String[] corsAllowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/error");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsAllowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "X-Session-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
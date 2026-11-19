package com.shiyi.trigger.http.interceptor;

import com.alibaba.fastjson2.JSON;
import com.shiyi.api.response.Response;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 极简 API Key 鉴权。
 * <p>
 * 这套服务对外暴露的是"能操作服务器终端"的接口（创建连接、打开终端、执行命令），
 * 一旦放到公网，没有鉴权等于把机器交出去。这里不做用户体系，只做一层共享密钥：
 * <pre>
 *   1. 请求头 {@code X-Api-Key: <key>}，或
 *   2. 请求头 {@code Authorization: Bearer <key>}
 * </pre>
 * 两种都支持，前端/脚本各自方便。
 * <p>
 * <b>安全策略</b>：{@code app.security.api-key} 没配置时**拒绝所有 /api 请求**（401）。
 * 之前"没配置就放行"等于默认零鉴权，任何人拿到地址就能调 chat_stream / approval / terminal；
 * 现在改成默认拒绝，本地调试请显式设置环境变量 APP_API_KEY。
 *
 * @author shiyi
 */
@Component
@Slf4j
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${app.security.api-key:}")
    private String apiKey;

    /** 只在第一次请求时提示一次，避免每个请求刷一条日志。 */
    private volatile boolean warned;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            if (!warned) {
                warned = true;
                log.error("[security] 未配置 app.security.api-key，已拒绝所有 /api 请求；请设置环境变量 APP_API_KEY");
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.toJSONString(Response.<Void>builder()
                    .code("401")
                    .info("服务未配置 API Key，已拒绝访问；请设置环境变量 APP_API_KEY")
                    .build()));
            return false;
        }
        if (apiKey.equals(resolveProvidedKey(request))) {
            return true;
        }
        log.warn("[security] 拒绝未授权请求 method={}, uri={}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(Response.<Void>builder()
                .code("401")
                .info("缺少或错误的 " + API_KEY_HEADER)
                .build()));
        return false;
    }

    private String resolveProvidedKey(HttpServletRequest request) {
        String provided = request.getHeader(API_KEY_HEADER);
        if (provided != null && !provided.isBlank()) {
            return provided.trim();
        }
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
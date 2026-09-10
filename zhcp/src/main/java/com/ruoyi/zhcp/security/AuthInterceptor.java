package com.ruoyi.zhcp.security;

import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.common.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTR = "LOGIN_USER";
    private final TokenService tokens;

    public AuthInterceptor(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        String raw = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : request.getParameter("token");
        if (raw == null || raw.isBlank()) {
            return deny(response, 401, "未登录或登录已过期");
        }
        try {
            LoginUser user = tokens.parse(raw);
            request.setAttribute(ATTR, user);
            return true;
        } catch (Exception e) {
            return deny(response, 401, "未登录或登录已过期");
        }
    }

    private boolean deny(HttpServletResponse response, int code, String msg) {
        try {
            response.setStatus(200);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\"}");
        } catch (Exception ignored) {
        }
        return false;
    }

    public static LoginUser current(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR);
        if (!(v instanceof LoginUser user)) {
            throw new ServiceException(401, "未登录或登录已过期");
        }
        return user;
    }
}

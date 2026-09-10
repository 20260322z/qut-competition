package com.ruoyi.zhcp.web;

import com.ruoyi.zhcp.common.AjaxResult;
import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.security.AuthInterceptor;
import com.ruoyi.zhcp.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class LoginController {
    private final AuthService auth;

    public LoginController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "name", "qut-zhcp");
    }

    @PostMapping({"/login", "/app/login"})
    public AjaxResult login(@RequestBody Map<String, String> body) {
        String role = "secretary";
        if ("student".equalsIgnoreCase(body.get("role")) || "学生".equals(body.get("role"))) role = "student";
        if ("secretary".equalsIgnoreCase(body.get("role")) || "团支书".equals(body.get("role"))) role = "secretary";
        String account = first(body, "username", "account", "userName");
        String password = first(body, "password", "pwd");
        Map<String, Object> data = auth.login(role, account, password);
        return AjaxResult.success(data).put("token", data.get("token"));
    }

    @GetMapping("/getInfo")
    public AjaxResult info(HttpServletRequest request) {
        LoginUser user = AuthInterceptor.current(request);
        return AjaxResult.success(Map.of(
                "user", auth.userMap(user),
                "roles", List.of(user.role),
                "permissions", List.of("*:*:*")
        ));
    }

    @GetMapping("/getRouters")
    public AjaxResult routers(HttpServletRequest request) {
        LoginUser user = AuthInterceptor.current(request);
        if (user.secretary()) {
            return AjaxResult.success(List.of(
                    menu("综测管理", "zhcp", "education", List.of(
                            menu("本班综测表", "table", "table", "/zhcp/table"),
                            menu("材料审核", "review", "form", "/zhcp/review")
                    ))
            ));
        }
        return AjaxResult.success(List.of(
                menu("我的综测", "mine", "user", "/zhcp/mine")
        ));
    }

    @PostMapping("/logout")
    public AjaxResult logout() {
        return AjaxResult.success("退出成功", null);
    }

    private static Map<String, Object> menu(String name, String path, String icon, Object childrenOrComponent) {
        if (childrenOrComponent instanceof List<?> list) {
            return Map.of("name", name, "path", "/" + path, "hidden", false,
                    "component", "Layout", "meta", Map.of("title", name, "icon", icon),
                    "children", list);
        }
        return Map.of("name", name, "path", path, "hidden", false,
                "component", childrenOrComponent, "meta", Map.of("title", name, "icon", icon));
    }

    private static String first(Map<String, String> body, String... keys) {
        for (String k : keys) {
            String v = body.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}

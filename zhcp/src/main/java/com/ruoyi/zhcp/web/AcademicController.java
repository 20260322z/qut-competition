package com.ruoyi.zhcp.web;

import com.ruoyi.zhcp.campus.JwClient;
import com.ruoyi.zhcp.common.AjaxResult;
import com.ruoyi.zhcp.common.ServiceException;
import com.ruoyi.zhcp.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class AcademicController {
    private final JwClient client;
    public AcademicController(JwClient client) { this.client = client; }

    @PostMapping("/app/academic/transcript")
    public AjaxResult transcript(HttpServletRequest request, @RequestBody Map<String, String> body) {
        var user = AuthInterceptor.current(request);
        if (!user.student()) throw new ServiceException("请使用本人学生身份读取成绩单");
        if (!"true".equals(body.get("consent"))) throw new ServiceException("请先确认读取本人教务成绩");
        String account = body.getOrDefault("account", "").trim();
        if (!account.equals(user.studentNo)) throw new ServiceException("只能读取当前登录学生本人的成绩");
        return AjaxResult.success(Map.of("items", client.transcript(account, body.get("password")),
                "mode", "preview", "message", "仅为预览，核对后再保存，不会自动覆盖本机成绩"));
    }
}

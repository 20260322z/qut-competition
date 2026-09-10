package com.ruoyi.zhcp.web;

import com.ruoyi.zhcp.common.AjaxResult;
import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.security.AuthInterceptor;
import com.ruoyi.zhcp.service.ExportService;
import com.ruoyi.zhcp.service.RuleIngestService;
import com.ruoyi.zhcp.service.ScoreService;
import com.ruoyi.zhcp.store.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
public class ZhcpController {
    private final ScoreService scores;
    private final ExportService export;
    private final RuleIngestService rules;
    private final Store store;

    public ZhcpController(ScoreService scores, ExportService export, RuleIngestService rules, Store store) {
        this.scores = scores;
        this.export = export;
        this.rules = rules;
        this.store = store;
    }

    @GetMapping({"/zhcp/class", "/app/zhcp/class"})
    public AjaxResult clazz(HttpServletRequest request) {
        return AjaxResult.success(scores.classTable(AuthInterceptor.current(request)));
    }

    @GetMapping({"/zhcp/score/{rosterId}", "/app/zhcp/score/{rosterId}"})
    public AjaxResult score(HttpServletRequest request, @PathVariable Long rosterId) {
        return AjaxResult.success(scores.studentScore(AuthInterceptor.current(request), rosterId));
    }

    @GetMapping({"/zhcp/mine", "/app/zhcp/mine"})
    public AjaxResult mine(HttpServletRequest request) {
        return AjaxResult.success(scores.studentScore(AuthInterceptor.current(request), null));
    }

    @PostMapping(value = {"/zhcp/upload", "/app/zhcp/upload"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult upload(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception {
        return AjaxResult.success(scores.upload(AuthInterceptor.current(request), file));
    }

    @PutMapping("/zhcp/score/{rosterId}")
    public AjaxResult update(HttpServletRequest request, @PathVariable Long rosterId, @RequestBody Map<String, Object> body) {
        scores.updateManual(AuthInterceptor.current(request), rosterId, body);
        return AjaxResult.success();
    }

    @PostMapping("/zhcp/item/{itemId}/confirm")
    public AjaxResult confirm(HttpServletRequest request, @PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        boolean ok = Boolean.TRUE.equals(body.get("ok")) || "true".equals(String.valueOf(body.get("ok")));
        scores.confirmItem(AuthInterceptor.current(request), itemId, ok);
        return AjaxResult.success();
    }

    @GetMapping("/zhcp/export")
    public void export(HttpServletRequest request, HttpServletResponse response) throws Exception {
        byte[] bytes = export.exportClass(AuthInterceptor.current(request));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=zhcp.xlsx");
        response.getOutputStream().write(bytes);
    }

    @GetMapping("/zhcp/rules")
    public AjaxResult ruleList(HttpServletRequest request) {
        return AjaxResult.success(rules.list(AuthInterceptor.current(request)));
    }

    @GetMapping("/zhcp/rules/{packId}")
    public AjaxResult ruleDetail(HttpServletRequest request, @PathVariable Long packId) {
        return AjaxResult.success(rules.detail(AuthInterceptor.current(request), packId));
    }

    @PostMapping(value = "/zhcp/rules/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult ruleUpload(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception {
        return AjaxResult.success(rules.ingest(AuthInterceptor.current(request), file));
    }

    @PutMapping("/zhcp/rules/{packId}")
    public AjaxResult ruleUpdate(HttpServletRequest request, @PathVariable Long packId, @RequestBody Map<String, Object> body) {
        rules.update(AuthInterceptor.current(request), packId, body);
        return AjaxResult.success();
    }

    @PostMapping("/zhcp/rules/{packId}/confirm")
    public AjaxResult ruleConfirm(HttpServletRequest request, @PathVariable Long packId) {
        rules.confirm(AuthInterceptor.current(request), packId);
        return AjaxResult.success();
    }

    @GetMapping("/zhcp/file/{fileId}")
    public void file(HttpServletRequest request, @PathVariable Long fileId, HttpServletResponse response) throws Exception {
        LoginUser user = AuthInterceptor.current(request);
        Map<String, Object> file = store.file(fileId);
        if (file == null) {
            response.setStatus(404);
            return;
        }
        if (user.student() && !user.userId.equals(((Number) file.get("user_id")).longValue())) {
            response.setStatus(403);
            return;
        }
        Path path = Path.of(String.valueOf(file.get("stored_path")));
        if (!Files.exists(path)) {
            response.setStatus(404);
            return;
        }
        response.setContentType(String.valueOf(file.getOrDefault("content_type", "application/octet-stream")));
        response.setHeader("Content-Disposition", "inline; filename=\"" + file.get("original_name") + "\"");
        try (InputStream in = Files.newInputStream(path)) {
            in.transferTo(response.getOutputStream());
        }
    }
}

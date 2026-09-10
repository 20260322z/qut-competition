package com.ruoyi.zhcp.campus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.zhcp.ai.VisionClient;
import com.ruoyi.zhcp.common.ServiceException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class XgClient {
    private static final String BASE = "https://zhxg.qut.edu.cn/xingateway/zhxg";
    private final ObjectMapper mapper = new ObjectMapper();
    private final VisionClient vision;

    public XgClient(VisionClient vision) {
        this.vision = vision;
    }

    public CampusProfile login(String account, String password) {
        BasicCookieStore cookies = new BasicCookieStore();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookies).build()) {
            get(http, "https://zhxg.qut.edu.cn/");
            JsonNode login = tryLogin(http, account, password);
            if (!ok(login)) {
                throw new ServiceException(msg(login, "学工账号或密码不正确"));
            }
            String token = tokenOf(login);
            CampusProfile p = new CampusProfile();
            p.studentNo = first(null, login, "LoginName", "UserNO", "XH");
            p.name = first(null, login, "TrueName", "UserName", "xm");
            p.college = first(null, login, "OrgName", "College", "XY");
            p.className = first(null, login, "ClassName", "BJMC", "BJ");
            if (p.studentNo == null) p.studentNo = account;
            fillMine(http, token, p);
            fillRoster(http, token, p);
            if (p.name == null || p.name.isBlank()) {
                throw new ServiceException("学工登录成功但未能读取姓名");
            }
            return p;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("学工平台暂时无法登录：" + e.getMessage());
        }
    }

    private JsonNode tryLogin(CloseableHttpClient http, String account, String password) throws Exception {
        JsonNode last = mapper.createObjectNode();
        for (int i = 0; i < 3; i++) {
            JsonNode cap = post(http, "/api/BaseData/GetAuthCode", mapper.createObjectNode(), null);
            JsonNode rv = cap.path("ResultValue");
            String key = rv.path("Key").asText();
            String image = rv.path("Image").asText();
            String code = recognizeCaptcha(image);
            ObjectNode body = mapper.createObjectNode();
            body.put("LoginName", account);
            body.put("Pwd", password);
            body.put("Code", code);
            body.put("key", key);
            body.put("ApplyType", 1);
            last = post(http, "/api/UserAuth/GetManUserLogin", body, null);
            if (ok(last)) return last;
            String m = msg(last, "");
            if (!m.contains("验证") && !m.contains("验证码") && last.path("ErrorCode").asInt() != 5001) {
                return last;
            }
        }
        return last;
    }

    private String recognizeCaptcha(String base64Png) {
        if (base64Png == null || base64Png.isBlank()) return "0000";
        String code = vision.readCaptcha(Base64.getDecoder().decode(base64Png));
        return code.isBlank() ? "0000" : code;
    }

    private void fillMine(CloseableHttpClient http, String token, CampusProfile p) throws Exception {
        for (String path : new String[]{"/api/UserInfo/GetLoginBaseInfo", "/api/UserInfo/GetBaseInfo", "/api/Student/UserGetMyUserData", "/api/ROPS/GetUserInfo"}) {
            JsonNode n = post(http, path, mapper.createObjectNode(), token);
            walk(n, p);
            if (p.name != null && p.college != null && p.className != null) return;
        }
    }

    private void fillRoster(CloseableHttpClient http, String token, CampusProfile p) throws Exception {
        ObjectNode empty = mapper.createObjectNode();
        for (String path : new String[]{
                "/api/Student/GetMyClassUserList",
                "/api/Student/GetMyCollegeClassUser",
                "/api/Student/GetMnageableStudentslist",
                "/api/Student/GetStudentDatalist",
                "/api/PerformanceEvaluation/CounselorGetMyStuList",
                "/api/ROPS/RopsClassData"
        }) {
            collectStudents(post(http, path, empty, token), p);
            if (p.roster.size() > 1) return;
        }
    }

    private void walk(JsonNode n, CampusProfile p) {
        if (n == null || n.isNull()) return;
        if (n.isObject()) {
            p.name = first(p.name, n, "TrueName", "XM", "xm", "UserName", "RealName", "StuName");
            p.studentNo = first(p.studentNo, n, "LoginName", "XH", "xh", "UserNO", "StuNo", "StudentNo");
            p.college = first(p.college, n, "OrgName", "XY", "College", "YXMC", "CollegeName");
            p.className = first(p.className, n, "ClassName", "BJ", "BJMC", "className");
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) walk(it.next().getValue(), p);
        } else if (n.isArray()) {
            for (JsonNode c : n) walk(c, p);
        }
    }

    private void collectStudents(JsonNode n, CampusProfile p) {
        if (n == null || n.isNull()) return;
        if (n.isArray()) {
            for (JsonNode c : n) collectStudents(c, p);
            return;
        }
        if (n.isObject()) {
            String no = first(null, n, "LoginName", "XH", "xh", "StuNo", "StudentNo", "UserNO");
            String name = first(null, n, "TrueName", "XM", "xm", "StuName", "UserName", "RealName");
            if (no != null && name != null) {
                p.roster.add(new CampusProfile.RosterRow(no, name));
            }
            p.college = first(p.college, n, "OrgName", "XY", "College", "YXMC");
            p.className = first(p.className, n, "ClassName", "BJ", "BJMC");
            n.fields().forEachRemaining(e -> {
                if (e.getValue().isContainerNode()) collectStudents(e.getValue(), p);
            });
        }
    }

    private boolean ok(JsonNode n) {
        if (n == null) return false;
        if (n.path("success").asBoolean(false)) return true;
        JsonNode rv = n.path("ResultValue");
        if (rv.isObject() && (rv.has("Token") || rv.has("Id") || rv.has("TrueName"))) return true;
        if (n.has("Token") || n.has("TrueName")) return true;
        int code = n.path("code").asInt(n.path("ResultCode").asInt(-1));
        return (code == 200 || code == 0) && n.path("ErrorCode").asInt(0) == 0;
    }

    private String tokenOf(JsonNode n) {
        String t = n.path("Token").asText(n.path("ResultValue").path("Token").asText(""));
        return t.isBlank() ? null : t;
    }

    private String msg(JsonNode n, String fallback) {
        String m = n.path("RequestMsg").asText(n.path("msg").asText(n.path("ErrValue").asText("")));
        return m.isBlank() ? fallback : m;
    }

    private JsonNode post(CloseableHttpClient http, String path, JsonNode body, String token) throws Exception {
        HttpPost post = new HttpPost(BASE + path);
        post.setHeader("User-Agent", "Mozilla/5.0");
        post.setHeader("Accept", "application/json, text/plain, */*");
        post.setHeader("Origin", "https://zhxg.qut.edu.cn");
        post.setHeader("Referer", "https://zhxg.qut.edu.cn/");
        if (token != null && !token.isBlank()) {
            post.setHeader("X-Token", token);
            post.setHeader("AppType", "2#3.0.0#1");
        }
        post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        String raw = http.execute(post, r -> EntityUtils.toString(r.getEntity(), StandardCharsets.UTF_8));
        try {
            return unwrap(mapper.readTree(raw));
        } catch (Exception e) {
            return mapper.createObjectNode().put("RequestMsg", raw.length() > 160 ? raw.substring(0, 160) : raw);
        }
    }

    private JsonNode unwrap(JsonNode n) {
        if (n != null && n.has("ResultValue") && n.get("ResultValue").isObject()
                && (n.get("ResultValue").has("Token") || n.get("ResultValue").has("TrueName") || n.get("ResultValue").has("Key"))) {
            ObjectNode merged = (ObjectNode) n.get("ResultValue").deepCopy();
            merged.set("ErrorCode", n.get("ErrorCode"));
            merged.set("RequestMsg", n.get("RequestMsg"));
            merged.set("ResultCode", n.get("ResultCode"));
            return merged;
        }
        return n;
    }

    private String get(CloseableHttpClient http, String url) throws Exception {
        HttpGet get = new HttpGet(url);
        get.setHeader("User-Agent", "Mozilla/5.0");
        return http.execute(get, r -> EntityUtils.toString(r.getEntity(), StandardCharsets.UTF_8));
    }

    private static String first(String cur, JsonNode n, String... keys) {
        if (cur != null && !cur.isBlank()) return cur;
        return text(n, keys);
    }

    private static String text(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) return v.asText().trim();
        }
        JsonNode data = n.get("data");
        if (data != null && data.isObject()) {
            for (String k : keys) {
                JsonNode v = data.get(k);
                if (v != null && v.isValueNode() && !v.asText().isBlank()) return v.asText().trim();
            }
        }
        return null;
    }
}

package com.ruoyi.zhcp.campus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.zhcp.common.ServiceException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.apache.hc.core5.util.Timeout;

@Component
public class JwClient {
    private static final String BASE = "http://jxgl.qut.edu.cn/jwglxt";
    private final ObjectMapper mapper = new ObjectMapper();

    /** User-triggered, read-only transcript preview. Never stores the supplied password. */
    public List<Map<String, Object>> transcript(String account, String password) {
        if (account == null || password == null || account.isBlank() || password.isBlank())
            throw new ServiceException("请输入本人的教务账号与密码");
        BasicCookieStore cookies = new BasicCookieStore();
        RequestConfig rc = RequestConfig.custom().setCookieSpec(StandardCookieSpec.RELAXED)
                .setConnectionRequestTimeout(Timeout.ofSeconds(20)).setResponseTimeout(Timeout.ofSeconds(35)).build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookies).setDefaultRequestConfig(rc).build()) {
            Document login = Jsoup.parse(get(http, BASE + "/xtgl/login_slogin.html"));
            if (!login.select("input[name=yzm], input[name=verifyCode]").isEmpty())
                throw new ServiceException("教务系统要求验证码，请先在学校页面完成验证或改用成绩文件导入");
            HttpPost signin = new HttpPost(BASE + "/xtgl/login_slogin.html");
            signin.setHeader("User-Agent", "Mozilla/5.0");
            signin.setHeader("Referer", BASE + "/xtgl/login_slogin.html");
            signin.setEntity(new UrlEncodedFormEntity(List.of(
                    new BasicNameValuePair("csrftoken", login.select("input[name=csrftoken]").attr("value")),
                    new BasicNameValuePair("language", "zh_CN"), new BasicNameValuePair("yhm", account.trim()),
                    new BasicNameValuePair("mm", encryptPassword(http, password))), StandardCharsets.UTF_8));
            String signed = http.execute(signin, r -> EntityUtils.toString(r.getEntity(), StandardCharsets.UTF_8));
            if (signed.contains("用户名或密码不正确") || !Jsoup.parse(signed).select("input[name=yhm]").isEmpty())
                throw new ServiceException("教务登录未完成，请检查账号、密码或学校验证要求");
            List<Map<String, Object>> result = new ArrayList<>();
            for (int page = 1; page <= 20; page++) {
                HttpPost query = new HttpPost(BASE + "/cjcx/cjcx_cxDgXscj.html?doType=query&gnmkdm=N305005");
                query.setHeader("User-Agent", "Mozilla/5.0");
                query.setHeader("X-Requested-With", "XMLHttpRequest");
                query.setEntity(new UrlEncodedFormEntity(List.of(
                        new BasicNameValuePair("xnm", ""), new BasicNameValuePair("xqm", ""),
                        new BasicNameValuePair("queryModel.showCount", "100"),
                        new BasicNameValuePair("queryModel.currentPage", String.valueOf(page)),
                        new BasicNameValuePair("queryModel.sortName", "xnm"),
                        new BasicNameValuePair("queryModel.sortOrder", "asc")), StandardCharsets.UTF_8));
                String raw = http.execute(query, r -> EntityUtils.toString(r.getEntity(), StandardCharsets.UTF_8));
                JsonNode root = mapper.readTree(raw);
                if (!root.has("items") || !root.path("items").isArray() || !root.has("totalCount"))
                    throw new ServiceException("教务成绩页面格式发生变化，未导入任何记录，请使用 CSV 导入");
                for (JsonNode row : root.path("items")) result.add(transcriptRow(row));
                if (result.size() >= root.path("totalCount").asInt()) return result;
                if (root.path("items").isEmpty()) break;
            }
            throw new ServiceException("未取得完整成绩单，未覆盖原记录，请稍后重试或使用 CSV 导入");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("教务成绩暂时读取失败；原记录仍保留，可改用 CSV 导入");
        }
    }

    static Map<String, Object> transcriptRow(JsonNode row) {
        Map<String, Object> value = new LinkedHashMap<>();
        String term = row.path("xqmmc").asText(row.path("xqm").asText());
        String name = row.path("kcmc").asText();
        if (name.isBlank()) throw new ServiceException("成绩存在无课程名称的记录，请核对原件");
        value.put("semester", row.path("xnm").asText() + "-" + term);
        value.put("course", name);
        value.put("credits", row.path("xf").asText());
        value.put("score", row.path("cj").asText());
        value.put("gpa", row.path("jd").asText());
        String exam = row.path("ksxz").asText() + row.path("ksxzmc").asText() + row.path("cxbj").asText();
        value.put("status", exam.contains("重修") ? "重修" : exam.contains("补考") ? "补考" : "正常");
        value.put("raw", row);
        return value;
    }

    public CampusProfile login(String account, String password) {
        BasicCookieStore cookies = new BasicCookieStore();
        RequestConfig rc = RequestConfig.custom().setCookieSpec(StandardCookieSpec.RELAXED).build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultCookieStore(cookies).setDefaultRequestConfig(rc).build()) {
            String loginHtml = get(http, BASE + "/xtgl/login_slogin.html");
            Document doc = Jsoup.parse(loginHtml);
            String csrf = doc.select("input[name=csrftoken]").attr("value");
            String encrypted = encryptPassword(http, password);
            List<NameValuePair> form = new ArrayList<>();
            form.add(new BasicNameValuePair("csrftoken", csrf));
            form.add(new BasicNameValuePair("language", "zh_CN"));
            form.add(new BasicNameValuePair("yhm", account));
            form.add(new BasicNameValuePair("mm", encrypted));
            HttpPost post = new HttpPost(BASE + "/xtgl/login_slogin.html");
            post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
            post.setHeader("User-Agent", "Mozilla/5.0");
            post.setHeader("Referer", BASE + "/xtgl/login_slogin.html");
            String body = http.execute(post, r -> EntityUtils.toString(r.getEntity(), StandardCharsets.UTF_8));
            if (body.contains("用户名或密码不正确") || body.contains("登录失败") || body.contains("name=\"yhm\"")) {
                throw new ServiceException("教务账号或密码不正确");
            }
            CampusProfile p = new CampusProfile();
            p.studentNo = account;
            fillProfile(http, p);
            if (p.name == null || p.name.isBlank()) {
                p.name = account;
            }
            if (p.college == null || p.college.isBlank()) p.college = "未知学院";
            if (p.className == null || p.className.isBlank()) p.className = "未知班级";
            return p;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("教务系统暂时无法登录：" + e.getMessage());
        }
    }

    private void fillProfile(CloseableHttpClient http, CampusProfile p) throws Exception {
        String[] urls = {
                BASE + "/xsxxxggl/xsxxwh_cxCkDgxsxx.html?gnmkdm=N100801",
                BASE + "/xtgl/index_cxYhxxIndex.html?xt=jw&localeKey=zh_CN&_=" + System.currentTimeMillis(),
                BASE + "/xtgl/login_cxCheckYh.html"
        };
        for (String url : urls) {
            parseInfo(get(http, url), p);
            if (p.name != null && !p.name.isBlank()) {
                return;
            }
        }
        parseInfo(get(http, BASE + "/xtgl/index_cxMenu.html?gnmkdm=index"), p);
    }

    private void parseInfo(String html, CampusProfile p) {
        if (html == null) return;
        String trimmed = html.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode n = mapper.readTree(trimmed);
                p.name = firstNonBlank(p.name, textOf(n, "xm", "XM", "userName"));
                p.college = firstNonBlank(p.college, textOf(n, "jg_id", "dwmc", "xy", "xymc", "college"));
                p.className = firstNonBlank(p.className, textOf(n, "bh_id", "bjmc", "bh", "className"));
                p.studentNo = firstNonBlank(p.studentNo, textOf(n, "xh", "xh_id"));
                if (p.name != null) return;
            } catch (Exception ignored) {
            }
        }
        Document d = Jsoup.parse(html);
        String text = d.text();
        if (p.name == null) {
            p.name = first(d, "#xm", ".xm", "[name=xm]");
            if (p.name == null) {
                p.name = match(text, "姓名[：:]\\s*([\\u4e00-\\u9fa5·]{2,20})");
            }
        }
        if (p.college == null) {
            p.college = first(d, "#dwmc", "#xy", "[name=dwmc]");
            if (p.college == null) {
                p.college = match(text, "学院[：:]\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,40})");
            }
        }
        if (p.className == null) {
            p.className = first(d, "#bjmc", "#bh_id", "[name=bjmc]");
            if (p.className == null) {
                p.className = match(text, "班级[：:]\\s*([\\u4e00-\\u9fa5A-Za-z0-9\\-]{2,40})");
            }
        }
        if (p.studentNo == null) {
            String xh = first(d, "#xh", "[name=xh]");
            if (xh != null) p.studentNo = xh;
        }
        try {
            JsonNode n = mapper.readTree(html.trim());
            p.name = firstNonBlank(p.name, textOf(n, "xm", "XM", "userName"));
            p.college = firstNonBlank(p.college, textOf(n, "dwmc", "xy", "xymc", "jg_id", "college"));
            p.className = firstNonBlank(p.className, textOf(n, "bjmc", "bh_id", "bh", "className"));
            p.studentNo = firstNonBlank(p.studentNo, textOf(n, "xh", "xh_id"));
        } catch (Exception ignored) {
        }
    }

    private String encryptPassword(CloseableHttpClient http, String password) throws Exception {
        String json = get(http, BASE + "/xtgl/login_getPublicKey.html?time=" + System.currentTimeMillis());
        if (json == null || !json.trim().startsWith("{")) {
            return password;
        }
        JsonNode n = mapper.readTree(json);
        String modulus = n.path("modulus").asText();
        String exponent = n.path("exponent").asText("AQAB");
        if (modulus.isBlank()) {
            return password;
        }
        BigInteger m = new BigInteger(1, java.util.Base64.getDecoder().decode(modulus));
        BigInteger e;
        try {
            e = new BigInteger(1, java.util.Base64.getDecoder().decode(exponent));
        } catch (Exception ex) {
            e = new BigInteger(exponent, 16);
        }
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(m, e));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
    }

    private String get(CloseableHttpClient http, String url) throws Exception {
        HttpGet get = new HttpGet(url);
        get.setHeader("User-Agent", "Mozilla/5.0");
        get.setHeader("Accept", "application/json, text/html, */*");
        get.setHeader("Referer", BASE + "/xtgl/login_slogin.html");
        return http.execute(get, r -> EntityUtils.toString(r.getEntity(), StandardCharsets.UTF_8));
    }

    private static String first(Document d, String... css) {
        for (String c : css) {
            String v = d.select(c).val();
            if (v == null || v.isBlank()) v = d.select(c).text();
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String match(String text, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String textOf(JsonNode n, String... keys) {
        for (String k : keys) {
            if (n.hasNonNull(k) && !n.get(k).asText().isBlank()) return n.get(k).asText().trim();
        }
        return null;
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }
}

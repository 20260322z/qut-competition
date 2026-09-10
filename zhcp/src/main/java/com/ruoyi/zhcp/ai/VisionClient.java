package com.ruoyi.zhcp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.zhcp.config.ZhcpProperties;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Component
public class VisionClient {
    private final ZhcpProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public VisionClient(ZhcpProperties props) {
        this.props = props;
    }

    public String readCaptcha(byte[] png) {
        JsonNode n = chat("图中是登录验证码，只返回验证码字符本身，不要解释。", List.of(image(png, "image/png")), false);
        return n.path("text").asText(n.path("raw").asText("")).replaceAll("[^A-Za-z0-9]", "");
    }

    public JsonNode extractEvidence(String studentName, String studentNo, ImagePart image, String extraText) {
        String prompt = """
                你只负责从原图抽取事实，不要计算综测分数，不要对照加分表。
                当前学生：姓名=%s，学号=%s。
                身份：只要图上或名单里出现当前学生姓名即可 name_match=true；允许多人并列。没有学号不能因此 reject。
                附加文本（可能不完整）：%s
                只输出一个 JSON：
                {
                  "material_type":"奖状|公示名单|盖章证明|系统截图|普通照片|其他",
                  "fields":{
                    "names_seen":["可见姓名"],
                    "student_no":"",
                    "event":"赛事或荣誉全称",
                    "award":"奖项原文",
                    "rank":"名次原文",
                    "level":"national|province|city|school|college|unknown",
                    "date":"",
                    "org":"主办或盖章单位",
                    "seal":true,
                    "role":"member|captain|author|unknown",
                    "kind":"contest|honor|paper|patent|project|sport|art|volunteer|collective|work|other"
                  },
                  "name_match":true,
                  "authenticity":"likely_genuine|suspicious|reject",
                  "reason":"中文理由，只谈图上看得见的内容",
                  "suspicious_signals":[]
                }
                不要编造看不见的章、姓名、奖项。普通手机翻拍且看不清章可标 suspicious，不要直接 reject。
                """.formatted(studentName, studentNo, extraText == null ? "" : extraText);
        return chat(prompt, image == null ? List.of() : List.of(image), true);
    }

    public JsonNode judge(String studentName, String evidenceJson, String title, String clause, double points, String ruleHint) {
        String prompt = """
                你是第二路复核员。只判断「条款选用和分数」是否与证据、规则提示一致，不要重新看图发明新奖项。
                学生姓名：%s
                证据JSON：%s
                系统已选：项目=%s，条款=%s，分数=%s
                规则提示：%s
                只输出 JSON：{"agree":true,"reason":"中文"}
                若姓名未出现、条款明显张冠李戴、或分数与提示表明显不符，agree=false。
                缺学号、多人奖状不是否决理由。
                """.formatted(studentName, evidenceJson, title, clause, points, ruleHint == null ? "" : ruleHint);
        return chat(prompt, List.of(), true);
    }

    public JsonNode extractRulePage(int page, ImagePart image, String text) {
        String prompt = """
                这是学院综测细则第 %d 页。抽出本页的分值表、条款、竞赛目录、补充说明。
                文本抽取（可能乱）：%s
                只输出 JSON：{"page":%d,"clauses":["原文要点"],"tables":[{"name":"","rows":[]}],"catalog":["竞赛名"],"notes":["补充"]}
                看不清的表不要编造数字。
                """.formatted(page, text == null ? "" : text, page);
        return chat(prompt, image == null ? List.of() : List.of(image), true);
    }

    public JsonNode assemblePack(String college, String title, String pagesJson, String schemaHint) {
        String prompt = """
                把各页抽取结果汇编成一份学院综测规则包。学院=%s，标题=%s。
                必须是可被程序读的 JSON，结构尽量贴近：
                %s
                各页抽取：
                %s
                要求：
                1. 分值以各页表格为准，不要用其他学院的分。
                2. contestCatalog2024 尽量收全本细则附录中的竞赛名。
                3. identityMatch.requireStudentNo 必须为 false，requireName 为 true。
                4. 只输出规则包 JSON，不要解释。
                """.formatted(college, title,
                schemaHint == null ? "{}" : schemaHint,
                pagesJson == null ? "" : pagesJson);
        return chat(prompt, List.of(), true);
    }

    private JsonNode chat(String text, List<ImagePart> images, boolean jsonOnly) {
        if (props.getDeepseekApiKey() == null || props.getDeepseekApiKey().isBlank()) {
            return error("未配置 DeepSeek");
        }
        RequestConfig cfg = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(30))
                .setConnectTimeout(Timeout.ofSeconds(30))
                .setResponseTimeout(Timeout.ofSeconds(180))
                .build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getVisionModel());
            ArrayNode messages = body.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            content.addObject().put("type", "text").put("text", text);
            for (ImagePart img : images) {
                if (img == null || img.base64 == null) continue;
                ObjectNode image = content.addObject();
                image.put("type", "image_url");
                image.putObject("image_url").put("url", "data:" + img.mime + ";base64," + img.base64);
            }
            HttpPost post = new HttpPost(props.getDeepseekBase().replaceAll("/$", "") + "/v1/chat/completions");
            post.setHeader("Authorization", "Bearer " + props.getDeepseekApiKey());
            post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
            String raw = http.execute(post, r -> EntityUtils.toString(r.getEntity(), StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(raw);
            String out = root.path("choices").path(0).path("message").path("content").asText("");
            if (jsonOnly) {
                int start = out.indexOf('{');
                int end = out.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    return mapper.readTree(out.substring(start, end + 1));
                }
                ObjectNode fallback = mapper.createObjectNode();
                fallback.put("raw", out);
                if (root.has("error")) fallback.put("error", root.path("error").path("message").asText(out));
                return fallback;
            }
            ObjectNode n = mapper.createObjectNode();
            n.put("text", out);
            n.put("raw", out);
            return n;
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private ObjectNode error(String msg) {
        ObjectNode n = mapper.createObjectNode();
        n.put("error", msg == null ? "视觉接口失败" : msg);
        return n;
    }

    public static ImagePart image(byte[] bytes, String mime) {
        ImagePart p = new ImagePart();
        p.mime = mime == null || mime.isBlank() ? "image/jpeg" : mime;
        p.base64 = Base64.getEncoder().encodeToString(bytes);
        return p;
    }

    public static class ImagePart {
        public String mime;
        public String base64;
    }
}

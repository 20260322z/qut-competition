package com.ruoyi.zhcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ruoyi.zhcp.ai.VisionClient;
import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.common.ServiceException;
import com.ruoyi.zhcp.config.ZhcpProperties;
import com.ruoyi.zhcp.store.Store;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RuleIngestService {
    private final Store store;
    private final VisionClient vision;
    private final ZhcpProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public RuleIngestService(Store store, VisionClient vision, ZhcpProperties props) {
        this.store = store;
        this.vision = vision;
        this.props = props;
    }

    public Map<String, Object> list(LoginUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("college", user.college);
        data.put("ready", store.hasConfirmedPack(user.college));
        data.put("packs", store.listPacks(user.college));
        return data;
    }

    public Map<String, Object> detail(LoginUser user, Long packId) {
        Map<String, Object> pack = requireOwn(user, packId);
        return pack;
    }

    public Map<String, Object> ingest(LoginUser user, MultipartFile file) throws Exception {
        if (!user.secretary()) throw new ServiceException(403, "仅团支书可上传本院细则");
        if (user.college == null || user.college.isBlank()) throw new ServiceException("登录信息缺少学院");
        String name = file.getOriginalFilename() == null ? "rules.pdf" : file.getOriginalFilename();
        if (!name.toLowerCase().endsWith(".pdf")) throw new ServiceException("请上传 PDF 细则");
        Path dir = Path.of(props.getUploadDir(), "rules");
        Files.createDirectories(dir);
        Path dest = dir.resolve(System.currentTimeMillis() + "-" + Path.of(name).getFileName());
        Files.write(dest, file.getBytes());
        List<Page> pages = render(file.getBytes());
        ArrayNode extracted = mapper.createArrayNode();
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (Page p : pages) {
            futures.add(CompletableFuture.supplyAsync(() -> vision.extractRulePage(p.no, p.image, p.text), pool));
        }
        for (CompletableFuture<JsonNode> f : futures) extracted.add(f.join());
        String schema = new String(new ClassPathResource("rules/xinkong-2024.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode assembled = vision.assemblePack(user.college, name.replace(".pdf", ""), extracted.toString(), schema);
        if (assembled.has("error") || !assembled.has("modules")) {
            assembled = mapper.createObjectNode()
                    .put("college", user.college)
                    .put("title", name)
                    .put("note", "自动汇编不完整，请人工改 JSON 后再确认")
                    .set("pages", extracted);
        }
        String code = "draft-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Long id = store.insertDraftPack(user.college, code, user.college + "综测细则（待确认）",
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(assembled), name);
        store.audit(user.userId, "ingest_rule", String.valueOf(id), name);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("packId", id);
        data.put("pages", pages.size());
        data.put("pack", store.pack(id));
        return data;
    }

    public void update(LoginUser user, Long packId, Map<String, Object> body) {
        Map<String, Object> pack = requireOwn(user, packId);
        String title = body.get("title") == null ? String.valueOf(pack.get("title")) : String.valueOf(body.get("title"));
        String json = body.get("contentJson") == null ? String.valueOf(pack.get("content_json")) : String.valueOf(body.get("contentJson"));
        try {
            mapper.readTree(json);
        } catch (Exception e) {
            throw new ServiceException("规则 JSON 无法解析");
        }
        store.updatePackJson(packId, title, json);
        store.audit(user.userId, "edit_rule", String.valueOf(packId), title);
    }

    public void confirm(LoginUser user, Long packId) {
        Map<String, Object> pack = requireOwn(user, packId);
        try {
            JsonNode n = mapper.readTree(String.valueOf(pack.get("content_json")));
            if (!n.has("modules") && !n.has("develop") && !n.has("moral")) {
                throw new ServiceException("草案还不是完整规则包，请先改 JSON");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("规则 JSON 无法解析");
        }
        store.confirmPack(packId, user.college);
        store.audit(user.userId, "confirm_rule", String.valueOf(packId), user.college);
    }

    private Map<String, Object> requireOwn(LoginUser user, Long packId) {
        if (!user.secretary()) throw new ServiceException(403, "仅团支书可管理规则包");
        Map<String, Object> pack = store.pack(packId);
        if (pack == null) throw new ServiceException("规则包不存在");
        if (!user.college.equals(String.valueOf(pack.get("college")))) throw new ServiceException(403, "只能管理本院规则包");
        return pack;
    }

    private List<Page> render(byte[] bytes) throws Exception {
        List<Page> list = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            int pages = Math.min(doc.getNumberOfPages(), 40);
            for (int i = 0; i < pages; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                BufferedImage img = renderer.renderImageWithDPI(i, 120);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                Page p = new Page();
                p.no = i + 1;
                p.text = stripper.getText(doc);
                p.image = VisionClient.image(out.toByteArray(), "image/png");
                list.add(p);
            }
        }
        return list;
    }

    private static class Page {
        int no;
        String text;
        VisionClient.ImagePart image;
    }
}

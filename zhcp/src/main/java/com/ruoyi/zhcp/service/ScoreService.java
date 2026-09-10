package com.ruoyi.zhcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.zhcp.ai.VisionClient;
import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.common.ServiceException;
import com.ruoyi.zhcp.config.ZhcpProperties;
import com.ruoyi.zhcp.score.ScoreEngine;
import com.ruoyi.zhcp.store.Store;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ScoreService {
    private final Store store;
    private final VisionClient vision;
    private final ScoreEngine engine;
    private final ZhcpProperties props;
    private final ExecutorService pool = Executors.newFixedThreadPool(10);

    public ScoreService(Store store, VisionClient vision, ScoreEngine engine, ZhcpProperties props) {
        this.store = store;
        this.vision = vision;
        this.engine = engine;
        this.props = props;
    }

    public Map<String, Object> classTable(LoginUser user) {
        Long classId = requireClass(user);
        List<Map<String, Object>> rows = store.classStudents(classId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("semester", props.getDefaultSemester());
        data.put("college", user.college);
        data.put("className", user.className);
        data.put("rows", rows);
        data.put("packReady", store.hasConfirmedPack(user.college));
        return data;
    }

    public Map<String, Object> studentScore(LoginUser user, Long rosterId) {
        Map<String, Object> roster = resolveRoster(user, rosterId);
        Long scoreId = store.ensureScore(((Number) roster.get("roster_id")).longValue(),
                ((Number) roster.get("class_id")).longValue(), props.getDefaultSemester());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roster", roster);
        data.put("score", store.score(scoreId));
        data.put("items", store.items(scoreId));
        data.put("files", store.filesOfScore(scoreId));
        boolean ready = store.hasConfirmedPack(user.college);
        data.put("packReady", ready);
        if (!ready) data.put("message", "本院细则未入库或未确认，暂不自动计分");
        return data;
    }

    public Map<String, Object> upload(LoginUser user, MultipartFile file) throws Exception {
        if (!user.student()) throw new ServiceException("仅学生可上传材料");
        Map<String, Object> roster = store.rosterOfUser(user.userId);
        if (roster == null) throw new ServiceException("未匹配到班级名单");
        Long scoreId = store.ensureScore(((Number) roster.get("roster_id")).longValue(),
                ((Number) roster.get("class_id")).longValue(), props.getDefaultSemester());
        Path dir = Path.of(props.getUploadDir(), String.valueOf(scoreId));
        Files.createDirectories(dir);
        List<VisionClient.ImagePart> images = new ArrayList<>();
        StringBuilder extra = new StringBuilder();
        List<Long> fileIds = new ArrayList<>();
        if (file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase().endsWith(".zip")) {
            try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    byte[] bytes = zis.readAllBytes();
                    fileIds.add(saveOne(scoreId, user.userId, dir, e.getName(), bytes, images, extra));
                }
            }
        } else {
            fileIds.add(saveOne(scoreId, user.userId, dir, file.getOriginalFilename(), file.getBytes(), images, extra));
        }
        if (images.isEmpty()) {
            throw new ServiceException("压缩包中没有可审核的图片或文档页面");
        }
        String rules = store.ruleJson(user.college);
        if (rules == null) {
            store.audit(user.userId, "upload_pending_pack", String.valueOf(scoreId), file.getOriginalFilename());
            Map<String, Object> data = studentScore(user, ((Number) roster.get("roster_id")).longValue());
            data.put("packReady", false);
            data.put("message", "材料已保存。本院细则未入库或未确认，暂不自动计分");
            return data;
        }
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        String extraText = extra.toString();
        for (VisionClient.ImagePart img : images) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> vision.extractEvidence(user.nickName, user.studentNo, img, extraText), pool));
        }
        List<ScoreEngine.Evidence> evidences = new ArrayList<>();
        for (int i = 0; i < futures.size() && i < fileIds.size(); i++) {
            JsonNode extracted = futures.get(i).join();
            store.updateFile(fileIds.get(i), extracted.path("material_type").asText("其他"),
                    extracted.path("authenticity").asText("pending"), extracted.path("reason").asText(""), extracted.toString());
            ScoreEngine.Evidence ev = ScoreEngine.fromExtract(i + 1, fileIds.get(i), extracted);
            if (extracted.has("error") && !ev.nameMatch) {
                ev.authenticity = "suspicious";
                ev.reason = extracted.path("error").asText("抽证失败");
            }
            evidences.add(ev);
        }
        store.clearItems(scoreId);
        ScoreEngine.Result applied = engine.apply(rules, evidences);
        judge(user.nickName, applied);
        engine.recap(applied, rules);
        for (ScoreEngine.Item it : applied.items) {
            store.addItem(scoreId, it.category, it.title, it.clause, it.raw, it.applied,
                    it.authenticity, it.reason, it.suggested, it.confirmed, it.rejected, it.evidence, it.ruleHint);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("moral_reward", applied.moralReward);
        fields.put("moral_deduct", applied.moralDeduct);
        fields.put("academic_reward", applied.academicReward);
        fields.put("academic_deduct", applied.academicDeduct);
        fields.put("body_reward", applied.bodyReward);
        fields.put("body_deduct", applied.bodyDeduct);
        fields.put("develop_work", applied.developWork);
        fields.put("develop_talent", applied.developTalent);
        fields.put("develop_tech", applied.developTech);
        store.updateScoreFields(scoreId, fields);
        store.refreshTotal(scoreId);
        store.refreshRanks(((Number) roster.get("class_id")).longValue(), props.getDefaultSemester());
        store.audit(user.userId, "upload_score", String.valueOf(scoreId), file.getOriginalFilename());
        Map<String, Object> data = studentScore(user, ((Number) roster.get("roster_id")).longValue());
        data.put("packReady", true);
        return data;
    }

    private void judge(String studentName, ScoreEngine.Result applied) {
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        for (ScoreEngine.Item it : applied.items) {
            if (it.rejected || it.raw <= 0) continue;
            jobs.add(CompletableFuture.runAsync(() -> {
                JsonNode j = vision.judge(studentName, it.evidenceJson, it.title, it.clause, it.raw, it.ruleHint);
                if (j.has("error") || !j.path("agree").asBoolean(true)) {
                    it.suggested = true;
                    it.confirmed = false;
                    it.applied = 0;
                    String why = j.path("reason").asText(j.path("error").asText("复核未通过"));
                    it.reason = (it.reason == null || it.reason.isBlank() ? "" : it.reason + "；") + "复核：" + why;
                }
            }, pool));
        }
        jobs.forEach(CompletableFuture::join);
    }

    public void updateManual(LoginUser user, Long rosterId, Map<String, Object> body) {
        if (!user.secretary()) throw new ServiceException(403, "仅团支书可改分");
        Map<String, Object> roster = resolveRoster(user, rosterId);
        Long scoreId = store.ensureScore(((Number) roster.get("roster_id")).longValue(),
                ((Number) roster.get("class_id")).longValue(), props.getDefaultSemester());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String k : List.of("moral_peer", "moral_reward", "moral_deduct", "academic_base", "academic_reward",
                "academic_deduct", "body_pe", "body_mental", "body_labor", "body_reward", "body_deduct",
                "develop_work", "develop_talent", "develop_tech", "signed")) {
            if (body.containsKey(k)) fields.put(k, body.get(k));
        }
        store.updateScoreFields(scoreId, fields);
        store.refreshTotal(scoreId);
        store.refreshRanks(((Number) roster.get("class_id")).longValue(), props.getDefaultSemester());
        store.audit(user.userId, "manual_score", String.valueOf(scoreId), body.toString());
    }

    public void confirmItem(LoginUser user, Long itemId, boolean ok) {
        if (!user.secretary()) throw new ServiceException(403, "仅团支书可确认材料");
        Map<String, Object> item = store.item(itemId);
        if (item == null) throw new ServiceException("加分项不存在");
        store.confirmItem(itemId, ok);
        Long scoreId = ((Number) item.get("score_id")).longValue();
        Map<String, Object> score = store.score(scoreId);
        if (ok) {
            String cat = String.valueOf(item.get("category"));
            String col = switch (cat) {
                case "moral_reward" -> "moral_reward";
                case "moral_deduct" -> "moral_deduct";
                case "academic_reward" -> "academic_reward";
                case "academic_deduct" -> "academic_deduct";
                case "body_reward" -> "body_reward";
                case "body_deduct" -> "body_deduct";
                case "develop_work" -> "develop_work";
                case "develop_talent" -> "develop_talent";
                case "develop_tech" -> "develop_tech";
                default -> null;
            };
            if (col != null) {
                double add = ((Number) item.get("raw_points")).doubleValue();
                double cur = score.get(col) == null ? 0 : ((Number) score.get(col)).doubleValue();
                store.updateScoreFields(scoreId, Map.of(col, cur + add));
                store.refreshTotal(scoreId);
            }
        }
        store.audit(user.userId, ok ? "confirm_item" : "reject_item", String.valueOf(itemId), "");
    }

    private Long saveOne(Long scoreId, Long userId, Path dir, String name, byte[] bytes,
                         List<VisionClient.ImagePart> images, StringBuilder extra) throws Exception {
        String safe = Path.of(name == null ? "file.bin" : name).getFileName().toString();
        Path dest = dir.resolve(System.currentTimeMillis() + "-" + safe);
        Files.write(dest, bytes);
        String lower = safe.toLowerCase();
        Long id = store.addFile(scoreId, userId, safe, dest.toString(), guess(lower));
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
            images.add(VisionClient.image(bytes, guess(lower)));
        } else if (lower.endsWith(".pdf")) {
            extra.append(extractPdfText(bytes)).append('\n');
            images.addAll(renderPdf(bytes));
        } else if (lower.endsWith(".docx")) {
            extra.append(extractDocx(bytes)).append('\n');
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            extra.append(extractExcel(bytes)).append('\n');
        }
        return id;
    }

    private List<VisionClient.ImagePart> renderPdf(byte[] bytes) {
        List<VisionClient.ImagePart> list = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), 20);
            for (int i = 0; i < pages; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 110);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                list.add(VisionClient.image(out.toByteArray(), "image/png"));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private String extractPdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        } catch (Exception e) {
            return "";
        }
    }

    private String extractDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String extractExcel(byte[] bytes) {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes); Workbook wb = WorkbookFactory.create(in)) {
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                sb.append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    for (Cell cell : row) sb.append(cell.toString()).append('\t');
                    sb.append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> resolveRoster(LoginUser user, Long rosterId) {
        if (user.student()) {
            Map<String, Object> mine = store.rosterOfUser(user.userId);
            if (mine == null) throw new ServiceException("未匹配到班级名单");
            return mine;
        }
        Long classId = requireClass(user);
        List<Map<String, Object>> rows = store.classStudents(classId);
        return rows.stream().filter(r -> rosterId.equals(((Number) r.get("roster_id")).longValue()))
                .findFirst().orElseThrow(() -> new ServiceException("学生不在本班"));
    }

    private Long requireClass(LoginUser user) {
        Long classId = store.classIdOfSecretary(user.userId);
        if (classId == null && user.student()) {
            Map<String, Object> r = store.rosterOfUser(user.userId);
            if (r != null) return ((Number) r.get("class_id")).longValue();
        }
        if (classId == null) throw new ServiceException("尚未同步班级");
        return classId;
    }

    private static String guess(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }
}

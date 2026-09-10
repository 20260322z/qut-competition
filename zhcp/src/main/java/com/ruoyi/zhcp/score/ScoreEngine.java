package com.ruoyi.zhcp.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ScoreEngine {
    private final ObjectMapper mapper = new ObjectMapper();

    public Result apply(String ruleJson, List<Evidence> evidences) {
        JsonNode rules = read(ruleJson);
        Result r = new Result();
        if (evidences == null || evidences.isEmpty()) return r;
        Map<String, List<Evidence>> groups = new LinkedHashMap<>();
        for (Evidence e : evidences) {
            String key = compact(firstNonBlank(e.event, e.award, "file-" + e.fileIndex));
            groups.computeIfAbsent(key.isBlank() ? "file-" + e.fileIndex : key, k -> new ArrayList<>()).add(e);
        }
        for (List<Evidence> group : groups.values()) {
            r.items.add(scoreGroup(rules, group));
        }
        Map<String, Double> sums = new LinkedHashMap<>();
        Double workBest = null;
        for (Item it : r.items) {
            if (it.applied <= 0) continue;
            if ("develop_work".equals(it.category)) {
                workBest = workBest == null ? it.applied : Math.max(workBest, it.applied);
                continue;
            }
            sums.merge(it.category, it.applied, Double::sum);
        }
        if (workBest != null) sums.put("develop_work", workBest);
        r.moralReward = cap(sums.getOrDefault("moral_reward", 0d), rules.path("caps").path("moralRewardOver"), 2, 0.1);
        r.moralDeduct = sums.getOrDefault("moral_deduct", 0d);
        r.academicReward = sums.getOrDefault("academic_reward", 0d);
        r.academicDeduct = sums.getOrDefault("academic_deduct", 0d);
        r.bodyReward = cap(sums.getOrDefault("body_reward", 0d), rules.path("caps").path("bodyRewardOver"), 2, 0.1);
        r.bodyDeduct = sums.getOrDefault("body_deduct", 0d);
        r.developWork = Math.min(3, sums.getOrDefault("develop_work", 0d));
        r.developTalent = cap(sums.getOrDefault("develop_talent", 0d), rules.path("caps").path("talentOver"), 2, 0.1);
        r.developTech = cap(sums.getOrDefault("develop_tech", 0d), rules.path("caps").path("techOver"), 5, 0.2);
        return r;
    }

    public void recap(Result r, String ruleJson) {
        JsonNode rules = read(ruleJson);
        Map<String, Double> sums = new LinkedHashMap<>();
        Double workBest = null;
        for (Item it : r.items) {
            if (it.applied <= 0) continue;
            if ("develop_work".equals(it.category)) {
                workBest = workBest == null ? it.applied : Math.max(workBest, it.applied);
            } else {
                sums.merge(it.category, it.applied, Double::sum);
            }
        }
        r.moralReward = cap(sums.getOrDefault("moral_reward", 0d), rules.path("caps").path("moralRewardOver"), 2, 0.1);
        r.moralDeduct = sums.getOrDefault("moral_deduct", 0d);
        r.academicReward = sums.getOrDefault("academic_reward", 0d);
        r.academicDeduct = sums.getOrDefault("academic_deduct", 0d);
        r.bodyReward = cap(sums.getOrDefault("body_reward", 0d), rules.path("caps").path("bodyRewardOver"), 2, 0.1);
        r.bodyDeduct = sums.getOrDefault("body_deduct", 0d);
        r.developWork = workBest == null ? 0 : Math.min(3, workBest);
        r.developTalent = cap(sums.getOrDefault("develop_talent", 0d), rules.path("caps").path("talentOver"), 2, 0.1);
        r.developTech = cap(sums.getOrDefault("develop_tech", 0d), rules.path("caps").path("techOver"), 5, 0.2);
    }

    private Item scoreGroup(JsonNode rules, List<Evidence> group) {
        Evidence best = group.get(0);
        boolean nameOk = false;
        boolean reject = false;
        boolean suspicious = false;
        List<Integer> indexes = new ArrayList<>();
        for (Evidence e : group) {
            indexes.add(e.fileIndex);
            if (e.nameMatch) nameOk = true;
            if ("reject".equals(e.authenticity)) reject = true;
            if ("suspicious".equals(e.authenticity)) suspicious = true;
            if (notBlank(e.event)) best = e;
        }
        if (!nameOk) reject = true;
        String blob = blob(group);
        Match m = match(rules, best, blob);
        Item row = new Item();
        row.category = m.category;
        row.title = m.title;
        row.clause = m.clause;
        row.raw = round(m.points);
        row.ruleHint = m.hint;
        row.evidenceJson = blob;
        row.evidence = indexes.toString();
        row.authenticity = reject ? "reject" : (suspicious ? "suspicious" : best.authenticity);
        row.reason = reject && !nameOk ? "材料未见当前学生姓名" : firstNonBlank(best.reason, m.hint);
        row.rejected = reject;
        row.suggested = !reject && (suspicious || m.uncertain);
        row.confirmed = !reject && !row.suggested && "likely_genuine".equals(row.authenticity);
        row.applied = (!reject && !row.suggested) ? row.raw : 0;
        return row;
    }

    private Match match(JsonNode rules, Evidence e, String blob) {
        String text = compact(blob + " " + nullToEmpty(e.event) + " " + nullToEmpty(e.award) + " " + nullToEmpty(e.kind));
        String level = normalizeLevel(e.level, text);
        String grade = normalizeGrade(e.award, e.rank, text);
        if (looks(text, "论文", "sci", "期刊", "ei")) return paper(rules, text);
        if (looks(text, "专利")) return patent(rules, text);
        if (looks(text, "课题", "立项", "结题")) return project(rules, level);
        if (looks(text, "美赛", "mcm", "icm") || isMcmAward(e.award, text)) return mcm(rules, text);
        if (looks(text, "acm") && looks(text, "山东")) return acmShandong(rules, grade, text);
        if (looks(text, "三下乡", "调研报告", "调研中国", "青年中国行")) return sxx(rules, level, grade, text);
        if (looks(text, "优秀班干部", "优秀学生干部", "优秀团员", "优秀学生", "千名优秀")) return studentHonor(rules, text);
        if (looks(text, "见义勇为", "拾金不昧", "帮弱助残", "抢险救灾")) return hero(rules, level);
        if (looks(text, "班集体", "学风班", "优秀团支部", "文明宿舍", "学风宿舍")) return collective(rules, text);
        if (looks(text, "积极分子", "先进个人") || "volunteer".equals(e.kind) || "honor".equals(e.kind))
            return honorPerson(rules, level, grade, text);
        if (looks(text, "班长", "团支书", "学生会", "社团负责", "舍长", "辅导员助理") || "work".equals(e.kind))
            return work(rules, text);
        if ("sport".equals(e.kind) || looks(text, "运动会", "田径", "篮球", "足球", "排球", "心理健康竞赛"))
            return sport(rules, level, grade, text);
        if ("art".equals(e.kind) || looks(text, "征文", "演讲", "摄影", "舞蹈", "动漫", "文艺", "主持"))
            return talent(rules, level, grade);
        return contest(rules, e, level, grade, text);
    }

    private Match contest(JsonNode rules, Evidence e, String level, String grade, String text) {
        JsonNode table = rules.path("develop").path("tech").path("contest").path(level);
        double base = table.path(grade).asDouble(0);
        if (base <= 0 && "participant".equals(grade)) base = 0;
        boolean in = inCatalog(rules, firstNonBlank(e.event, text));
        double rate = in ? rules.path("catalogInRate").asDouble(1) : rules.path("catalogOutRate").asDouble(0.5);
        double special = 1;
        String hint = in ? "目录内" : "目录外×" + rate;
        if (looks(text, "互联网+", "互联网＋", "中国国际大学生创新", "挑战杯", "创青春")) {
            JsonNode sp = rules.path("develop").path("tech").path("special").path("internetPlusChallengeCup");
            special = looks(text, "队长", "第一负责人") || "captain".equals(e.role)
                    ? sp.path("captain").asDouble(2.5) : sp.path("member").asDouble(2);
            hint += "；互联网+/挑战杯/创青春×" + special;
        }
        double points = base * rate * special;
        boolean uncertain = base <= 0 || "unknown".equals(level);
        return m("develop_tech", firstNonBlank(e.event, "科技竞赛"), "第十六条（二）", points, hint, uncertain);
    }

    private Match mcm(JsonNode rules, String text) {
        JsonNode map = rules.path("develop").path("tech").path("mcmIcm");
        String key = "U";
        if (looks(text, "o奖", "outstanding")) key = "O";
        else if (looks(text, "f奖", "finalist")) key = "F";
        else if (looks(text, "m奖", "meritorious")) key = "M";
        else if (looks(text, "h奖", "honorable")) key = "H";
        else if (looks(text, "s奖")) key = "S";
        double points = map.path(key).path("points").asDouble(0);
        return m("develop_tech", "美国大学生数学建模竞赛" + key, "美赛说明", points, "美赛" + key + "均分到每人", false);
    }

    private Match acmShandong(JsonNode rules, String grade, String text) {
        JsonNode n = rules.path("develop").path("tech").path("special").path("acmShandong");
        String g = grade;
        if ("participant".equals(g) || looks(text, "参与", "参赛奖")) g = "participant";
        double points = n.path(g).asDouble(n.path("participant").asDouble(1));
        return m("develop_tech", "ACM-ICPC山东省赛", "第十六条特别说明", points, "ACM山东单独表", false);
    }

    private Match paper(JsonNode rules, String text) {
        JsonNode p = rules.path("develop").path("tech").path("paper");
        boolean front = looks(text, "第一", "第二", "1-2", "12作") || !looks(text, "第三", "第四");
        if (looks(text, "sci")) return m("develop_tech", "SCI论文", "第十六条（一）", 0, String.valueOf(p.path("sci").asText()), true);
        JsonNode row = looks(text, "核心") ? p.path("core") : p.path("normal");
        double points = front ? row.path("12").asDouble(0) : row.path("34").asDouble(0);
        return m("develop_tech", looks(text, "核心") ? "核心期刊论文" : "普通期刊论文", "第十六条（一）", points, "第一单位须青岛理工大学", false);
    }

    private Match patent(JsonNode rules, String text) {
        JsonNode p = rules.path("develop").path("tech").path("patent");
        boolean front = !looks(text, "第4", "第5", "第6", "第7", "4至7");
        JsonNode row = looks(text, "发明") ? p.path("invention") : (looks(text, "外观") ? p.path("appearance") : p.path("utility"));
        double points = front ? row.path("1to3").asDouble(0) : row.path("4to7").asDouble(0);
        return m("develop_tech", looks(text, "发明") ? "发明专利" : "实用新型/外观专利", "第十六条（四）", points, "", false);
    }

    private Match project(JsonNode rules, String level) {
        JsonNode p = rules.path("develop").path("tech").path("project").path(level);
        double points = p.path("1to4").asDouble(0);
        return m("develop_tech", "科研课题", "第十六条（三）", points, "同一课题多级立项取最高", points <= 0);
    }

    private Match sxx(JsonNode rules, String level, String grade, String text) {
        if (looks(text, "30强")) {
            level = "national";
            grade = "first";
        } else if (looks(text, "100强")) {
            level = "national";
            grade = "second";
        }
        double points = findReward(rules, "m-sxx-report").path("table").path(level).path(grade).asDouble(0);
        return m("moral_reward", "三下乡调研报告", "第六条（一）", points, "", points <= 0);
    }

    private Match honorPerson(JsonNode rules, String level, String grade, String text) {
        JsonNode pts = findReward(rules, "m-honor-person").path("points");
        if (looks(text, "参与未获奖", "未获奖") || "participant".equals(grade)) {
            return m("moral_reward", "社会实践/志愿参与", "第六条（一）", pts.path("participant").asDouble(0.1), "", false);
        }
        String lv = "city".equals(level) ? "city" : level;
        if ("school".equals(level)) lv = "school";
        double points = pts.path(lv).asDouble(pts.path("school").asDouble(1));
        return m("moral_reward", "社会实践/志愿先进个人", "第六条（一）", points, "团队不区分贡献则平摊，队长双倍平均分", false);
    }

    private Match studentHonor(JsonNode rules, String text) {
        JsonNode pts = findReward(rules, "m-student-honor").path("points");
        if (looks(text, "学习标兵") || (looks(text, "优秀学生") && looks(text, "综测", "无差额"))) {
            return m("moral_reward", "发钱或无差额荣誉", "补充说明", 0, "发钱或综测无差额荣誉不加分", false);
        }
        double points = 0.3;
        String title = "校级优秀团员";
        if (looks(text, "省级") && looks(text, "班干部", "优秀学生")) {
            points = pts.path("province_cadre_or_student").asDouble(1);
            title = "省级优秀班干部/学生";
        } else if (looks(text, "市级", "千名")) {
            points = pts.path("city_thousand_or_student").asDouble(0.75);
            title = "市级优秀学生";
        } else if (looks(text, "学生干部", "班干部")) {
            points = pts.path("school_cadre").asDouble(0.5);
            title = "校级优秀学生干部";
        } else {
            points = pts.path("school_league").asDouble(0.3);
        }
        return m("moral_reward", title, "补充说明", points, "优秀团员与班干部取高不累加", false);
    }

    private Match hero(JsonNode rules, String level) {
        JsonNode pts = findReward(rules, "m-hero").path("points");
        String lv = "school".equals(level) ? "school" : level;
        return m("moral_reward", "见义勇为等表彰", "第六条（二）", pts.path(lv).asDouble(0), "", false);
    }

    private Match collective(JsonNode rules, String text) {
        JsonNode pts = findReward(rules, "m-collective").path("points");
        double points = pts.path("school_good").asDouble(0.2);
        if (looks(text, "省级")) points = pts.path("province_class").asDouble(1);
        else if (looks(text, "标兵", "十佳学风宿舍")) points = pts.path("school_model").asDouble(0.3);
        return m("moral_reward", "班团宿舍集体荣誉", "第六条（四）", points, "干部再加分值1/2或1/4", false);
    }

    private Match work(JsonNode rules, String text) {
        JsonNode arr = rules.path("develop").path("work");
        JsonNode hit = null;
        if (looks(text, "校团委兼职副书记")) hit = findWork(arr, "w-school-vice");
        else if (looks(text, "校团委副部长", "校团委副主任")) hit = findWork(arr, "w-school-minister");
        else if (looks(text, "院团委兼职副书记")) hit = findWork(arr, "w-college-vice");
        else if (looks(text, "主席")) hit = findWork(arr, "w-union-pres");
        else if (looks(text, "班长", "团支部书记", "团支书")) hit = findWork(arr, "w-class-lead");
        else if (looks(text, "辅导员助理", "副班长")) hit = findWork(arr, "w-assistant");
        else if (looks(text, "社团")) hit = findWork(arr, "w-club");
        else if (looks(text, "舍长", "班委")) hit = findWork(arr, "w-committee");
        else hit = findWork(arr, "w-dept-lead");
        double points = hit == null ? 0 : hit.path("duty").asDouble(0);
        if (looks(text, "优秀") && hit != null) points = hit.path("excellent").asDouble(points);
        return m("develop_work", hit == null ? "学生干部" : hit.path("title").asText("学生干部"),
                "第十四条", points, "兼任取最高，不合格不加", points <= 0);
    }

    private Match sport(JsonNode rules, String level, String grade, String text) {
        String g = grade;
        if (looks(text, "第1名") || "1".equals(compact(text))) g = "first";
        JsonNode table = findBodySport(rules, "national".equals(level) || "province".equals(level)
                ? ("national".equals(level) ? "national" : "province") : level);
        double points = table.path(g).asDouble(0);
        return m("body_reward", "体育/心理健康竞赛", "第十二条", points, "第1名/一等；2-3名/二等；4-8名/三等", points <= 0);
    }

    private Match talent(JsonNode rules, String level, String grade) {
        String lv = "city".equals(level) ? "city" : level;
        if ("school".equals(level)) lv = "school";
        JsonNode table = rules.path("develop").path("talent").path("table").path(lv.equals("city") || lv.equals("school") ? (lv.equals("city") ? "city" : "school") : lv);
        if ("city".equals(level) || "school".equals(level)) {
            table = rules.path("develop").path("talent").path("table").path("city");
            if ("school".equals(level)) table = rules.path("develop").path("talent").path("table").path("school");
        }
        double points = table.path(grade).asDouble(0);
        return m("develop_talent", "文化艺术比赛", "第十五条", points, "", points <= 0);
    }

    private JsonNode findReward(JsonNode rules, String id) {
        JsonNode arr = rules.path("moral").path("rewards");
        if (arr.isArray()) {
            for (JsonNode n : arr) if (id.equals(n.path("id").asText())) return n;
        }
        return mapper.createObjectNode();
    }

    private JsonNode findWork(JsonNode arr, String id) {
        if (arr.isArray()) {
            for (JsonNode n : arr) if (id.equals(n.path("id").asText())) return n;
        }
        return null;
    }

    private JsonNode findBodySport(JsonNode rules, String level) {
        JsonNode arr = rules.path("body").path("rewards");
        if (arr.isArray() && !arr.isEmpty()) return arr.get(0).path("table").path(level);
        return mapper.createObjectNode();
    }

    private boolean inCatalog(JsonNode rules, String title) {
        String key = compact(title);
        if (key.isBlank()) return false;
        if (key.contains("山东省科技创新大赛")) return true;
        JsonNode catalog = rules.path("contestCatalog2024");
        if (catalog.isArray()) {
            for (JsonNode n : catalog) {
                String name = compact(n.asText());
                if (!name.isBlank() && (key.contains(name) || name.contains(key) || overlap(key, name))) return true;
            }
        }
        JsonNode aliases = rules.path("catalogAliases");
        if (aliases.isObject()) {
            var it = aliases.fields();
            while (it.hasNext()) {
                var e = it.next();
                String alias = compact(e.getKey());
                if (!alias.isBlank() && (key.contains(alias) || alias.contains(key))) return true;
            }
        }
        return false;
    }

    private static boolean overlap(String a, String b) {
        if (a.length() < 6 || b.length() < 6) return false;
        return a.contains(b.substring(0, Math.min(8, b.length()))) || b.contains(a.substring(0, Math.min(8, a.length())));
    }

    private static Match m(String cat, String title, String clause, double points, String hint, boolean uncertain) {
        Match x = new Match();
        x.category = cat;
        x.title = title;
        x.clause = clause;
        x.points = points;
        x.hint = hint;
        x.uncertain = uncertain || points < 0;
        return x;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static double cap(double value, JsonNode node, double defT, double defR) {
        double t = node.path("threshold").asDouble(defT);
        double r = node.path("rate").asDouble(defR);
        if (value <= t) return value;
        return t + (value - t) * r;
    }

    private static String normalizeLevel(String level, String text) {
        if (level != null && List.of("national", "province", "city", "school", "college").contains(level)) return level;
        if (looks(text, "国际", "全国", "国家", "国赛")) return "national";
        if (looks(text, "省", "部级")) return "province";
        if (looks(text, "市")) return "city";
        if (looks(text, "校")) return "school";
        if (looks(text, "院")) return "college";
        return "unknown";
    }

    private static String normalizeGrade(String award, String rank, String text) {
        String t = compact(award + " " + rank + " " + text);
        if (looks(t, "特等", "金奖", "一等", "第1名", "第2名", "冠军")) return "first";
        if (looks(t, "二等", "银奖", "第3名", "第4名", "第5名")) return "second";
        if (looks(t, "三等", "铜奖", "第6名", "第7名", "第8名")) return "third";
        if (looks(t, "参与", "成功参赛", "参赛奖")) return "participant";
        return "third";
    }

    private static boolean isMcmAward(String award, String text) {
        String t = compact(award + text);
        return looks(t, "o奖", "f奖", "m奖", "h奖", "s奖", "u奖", "outstanding", "finalist", "meritorious");
    }

    private static boolean looks(String text, String... keys) {
        String t = compact(text);
        for (String k : keys) {
            if (t.contains(compact(k))) return true;
        }
        return false;
    }

    private static String blob(List<Evidence> group) {
        StringBuilder sb = new StringBuilder();
        for (Evidence e : group) {
            sb.append(nullToEmpty(e.event)).append(' ').append(nullToEmpty(e.award)).append(' ')
                    .append(nullToEmpty(e.rank)).append(' ').append(nullToEmpty(e.level)).append(' ')
                    .append(nullToEmpty(e.kind)).append(' ').append(nullToEmpty(e.org)).append('\n');
        }
        return sb.toString();
    }

    private static String compact(String s) {
        return s == null ? "" : s.replaceAll("[\\s\"“”‘’·\\-—()（）]", "").toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return "";
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    public static Evidence fromExtract(int index, Long fileId, JsonNode n) {
        Evidence e = new Evidence();
        e.fileIndex = index;
        e.fileId = fileId;
        e.materialType = n.path("material_type").asText("其他");
        e.nameMatch = n.path("name_match").asBoolean(false);
        e.authenticity = n.path("authenticity").asText("pending");
        e.reason = n.path("reason").asText("");
        JsonNode f = n.path("fields");
        e.event = f.path("event").asText("");
        e.award = f.path("award").asText("");
        e.rank = f.path("rank").asText("");
        e.level = f.path("level").asText("unknown");
        e.org = f.path("org").asText("");
        e.kind = f.path("kind").asText("other");
        e.role = f.path("role").asText("unknown");
        e.seal = f.path("seal").asBoolean(false);
        return e;
    }

    public static class Evidence {
        public int fileIndex;
        public Long fileId;
        public String materialType, authenticity, reason, event, award, rank, level, org, kind, role;
        public boolean nameMatch, seal;
    }

    private static class Match {
        String category, title, clause, hint;
        double points;
        boolean uncertain;
    }

    public static class Result {
        public final List<Item> items = new ArrayList<>();
        public double moralReward, moralDeduct, academicReward, academicDeduct;
        public double bodyReward, bodyDeduct, developWork, developTalent, developTech;
    }

    public static class Item {
        public String category, title, clause, authenticity, reason, evidence, evidenceJson, ruleHint;
        public double raw, applied;
        public boolean suggested, confirmed, rejected;
    }
}

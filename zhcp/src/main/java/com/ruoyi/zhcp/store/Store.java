package com.ruoyi.zhcp.store;

import com.ruoyi.zhcp.common.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Repository
public class Store {
    private final JdbcTemplate jdbc;

    public Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LoginUser> USER = (rs, i) -> {
        LoginUser u = new LoginUser();
        u.userId = rs.getLong("user_id");
        u.userName = rs.getString("user_name");
        u.nickName = rs.getString("nick_name");
        u.role = rs.getString("role");
        u.studentNo = rs.getString("student_no");
        u.college = rs.getString("college");
        u.className = rs.getString("class_name");
        return u;
    };

    public LoginUser findUser(String userName) {
        List<LoginUser> list = jdbc.query("SELECT * FROM sys_user WHERE user_name=?", USER, userName);
        return list.isEmpty() ? null : list.get(0);
    }

    public LoginUser findUserById(Long id) {
        List<LoginUser> list = jdbc.query("SELECT * FROM sys_user WHERE user_id=?", USER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long upsertUser(String userName, String nick, String role, String studentNo,
                           String college, String className, String campusAccount, String encPwd) {
        LoginUser exist = findUser(userName);
        if (exist != null) {
            jdbc.update("""
                    UPDATE sys_user SET nick_name=?, role=?, student_no=?, college=?, class_name=?,
                    campus_account=?, campus_password_enc=?, update_time=NOW() WHERE user_id=?
                    """, nick, role, studentNo, college, className, campusAccount, encPwd, exist.userId);
            return exist.userId;
        }
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO sys_user(user_name,nick_name,role,student_no,college,class_name,campus_account,campus_password_enc)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, userName);
            ps.setString(2, nick);
            ps.setString(3, role);
            ps.setString(4, studentNo);
            ps.setString(5, college);
            ps.setString(6, className);
            ps.setString(7, campusAccount);
            ps.setString(8, encPwd);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Long upsertClass(String college, String className, String semester, Long secretaryId) {
        List<Long> ids = jdbc.query("SELECT class_id FROM zhcp_class WHERE college=? AND class_name=? AND semester=?",
                (rs, i) -> rs.getLong(1), college, className, semester);
        if (!ids.isEmpty()) {
            if (secretaryId != null) {
                jdbc.update("UPDATE zhcp_class SET secretary_user_id=? WHERE class_id=?", secretaryId, ids.get(0));
            }
            return ids.get(0);
        }
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO zhcp_class(college,class_name,secretary_user_id,semester) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, college);
            ps.setString(2, className);
            if (secretaryId == null) ps.setObject(3, null);
            else ps.setLong(3, secretaryId);
            ps.setString(4, semester);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void upsertRoster(Long classId, String studentNo, String name) {
        jdbc.update("""
                INSERT INTO zhcp_roster(class_id,student_no,student_name) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE student_name=VALUES(student_name)
                """, classId, studentNo, name);
    }

    public Map<String, Object> matchRoster(String studentNo, String name, String college, String className) {
        List<Map<String, Object>> byNo = jdbc.queryForList("""
                SELECT r.*, c.college, c.class_name, c.semester FROM zhcp_roster r
                JOIN zhcp_class c ON c.class_id=r.class_id WHERE r.student_no=?
                """, studentNo);
        if (!byNo.isEmpty()) {
            return byNo.get(0);
        }
        List<Map<String, Object>> byName = jdbc.queryForList("""
                SELECT r.*, c.college, c.class_name, c.semester FROM zhcp_roster r
                JOIN zhcp_class c ON c.class_id=r.class_id
                WHERE r.student_name=? AND c.college=? AND c.class_name=?
                """, name, college, className);
        return byName.isEmpty() ? null : byName.get(0);
    }

    public void markRegistered(Long rosterId, Long userId) {
        jdbc.update("UPDATE zhcp_roster SET registered=1, user_id=? WHERE roster_id=?", userId, rosterId);
    }

    public List<Map<String, Object>> classStudents(Long classId) {
        return jdbc.queryForList("""
                SELECT r.*, s.score_id, s.total_score, s.moral_peer, s.moral_reward, s.moral_deduct,
                s.academic_base, s.academic_reward, s.academic_deduct,
                s.body_pe, s.body_mental, s.body_labor, s.body_reward, s.body_deduct,
                s.develop_work, s.develop_talent, s.develop_tech, s.major_rank, s.zhcp_rank, s.signed
                FROM zhcp_roster r
                LEFT JOIN zhcp_score s ON s.roster_id=r.roster_id
                WHERE r.class_id=? ORDER BY r.student_no
                """, classId);
    }

    public Long classIdOfSecretary(Long userId) {
        List<Long> ids = jdbc.query("SELECT class_id FROM zhcp_class WHERE secretary_user_id=? ORDER BY class_id DESC",
                (rs, i) -> rs.getLong(1), userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Map<String, Object> rosterOfUser(Long userId) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM zhcp_roster WHERE user_id=?", userId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long ensureScore(Long rosterId, Long classId, String semester) {
        List<Long> ids = jdbc.query("SELECT score_id FROM zhcp_score WHERE roster_id=? AND semester=?",
                (rs, i) -> rs.getLong(1), rosterId, semester);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO zhcp_score(roster_id,class_id,semester) VALUES(?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, rosterId);
            ps.setLong(2, classId);
            ps.setString(3, semester);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Map<String, Object> score(Long scoreId) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM zhcp_score WHERE score_id=?", scoreId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateScoreFields(Long scoreId, Map<String, Object> fields) {
        if (fields.isEmpty()) return;
        StringBuilder sb = new StringBuilder("UPDATE zhcp_score SET ");
        Object[] args = new Object[fields.size() + 1];
        int i = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (i > 0) sb.append(',');
            sb.append(e.getKey()).append("=?");
            args[i++] = e.getValue();
        }
        sb.append(" WHERE score_id=?");
        args[i] = scoreId;
        jdbc.update(sb.toString(), args);
    }

    public void refreshTotal(Long scoreId) {
        jdbc.update("""
                UPDATE zhcp_score SET total_score =
                (moral_peer+moral_reward-moral_deduct)
                +(academic_base+academic_reward-academic_deduct)
                +(body_pe+body_mental+body_labor+body_reward-body_deduct)
                +(develop_work+develop_talent+develop_tech)
                WHERE score_id=?
                """, scoreId);
    }

    public void refreshRanks(Long classId, String semester) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT score_id FROM zhcp_score WHERE class_id=? AND semester=? ORDER BY total_score DESC, score_id
                """, classId, semester);
        int rank = 1;
        for (Map<String, Object> row : rows) {
            jdbc.update("UPDATE zhcp_score SET zhcp_rank=? WHERE score_id=?", rank++, row.get("score_id"));
        }
    }

    public Long addFile(Long scoreId, Long userId, String name, String path, String type) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO zhcp_file(score_id,user_id,original_name,stored_path,content_type)
                    VALUES(?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (scoreId == null) ps.setObject(1, null);
            else ps.setLong(1, scoreId);
            ps.setLong(2, userId);
            ps.setString(3, name);
            ps.setString(4, path);
            ps.setString(5, type);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void updateFile(Long fileId, String materialType, String auth, String reason, String extracted) {
        jdbc.update("""
                UPDATE zhcp_file SET material_type=?, authenticity=?, authenticity_reason=?, extracted_json=?
                WHERE file_id=?
                """, materialType, auth, reason, extracted, fileId);
    }

    public Map<String, Object> file(Long fileId) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM zhcp_file WHERE file_id=?", fileId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Map<String, Object>> filesOfScore(Long scoreId) {
        return jdbc.queryForList("SELECT file_id,original_name,material_type,authenticity,authenticity_reason,create_time FROM zhcp_file WHERE score_id=?", scoreId);
    }

    public void clearItems(Long scoreId) {
        jdbc.update("DELETE FROM zhcp_item WHERE score_id=?", scoreId);
    }

    public void addItem(Long scoreId, String category, String title, String clause, double raw, double applied,
                        String auth, String reason, boolean suggested, boolean confirmed, boolean rejected, String evidenceIds) {
        addItem(scoreId, category, title, clause, raw, applied, auth, reason, suggested, confirmed, rejected, evidenceIds, null);
    }

    public void addItem(Long scoreId, String category, String title, String clause, double raw, double applied,
                        String auth, String reason, boolean suggested, boolean confirmed, boolean rejected,
                        String evidenceIds, String extraJson) {
        jdbc.update("""
                INSERT INTO zhcp_item(score_id,category,title,clause,raw_points,applied_points,authenticity,authenticity_reason,suggested,confirmed,rejected,evidence_ids,extra_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, scoreId, category, title, clause, raw, applied, auth, reason, suggested ? 1 : 0, confirmed ? 1 : 0, rejected ? 1 : 0, evidenceIds, extraJson);
    }

    public List<Map<String, Object>> items(Long scoreId) {
        return jdbc.queryForList("SELECT * FROM zhcp_item WHERE score_id=? ORDER BY item_id", scoreId);
    }

    public Map<String, Object> item(Long itemId) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM zhcp_item WHERE item_id=?", itemId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void confirmItem(Long itemId, boolean ok) {
        jdbc.update("UPDATE zhcp_item SET confirmed=?, rejected=? WHERE item_id=?", ok ? 1 : 0, ok ? 0 : 1, itemId);
    }

    public void audit(Long userId, String action, String target, String detail) {
        jdbc.update("INSERT INTO zhcp_audit(user_id,action,target,detail) VALUES(?,?,?,?)", userId, action, target, detail);
    }

    public String ruleJson(String college) {
        List<String> exact = jdbc.query("""
                SELECT content_json FROM zhcp_rule_pack
                WHERE college=? AND enabled=1 AND IFNULL(confirmed,1)=1 LIMIT 1
                """, (rs, i) -> rs.getString(1), college);
        if (!exact.isEmpty()) return exact.get(0);
        List<Map<String, Object>> all = jdbc.queryForList("""
                SELECT college, content_json FROM zhcp_rule_pack
                WHERE enabled=1 AND IFNULL(confirmed,1)=1
                """);
        for (Map<String, Object> row : all) {
            if (collegeMatches(college, String.valueOf(row.get("college")), String.valueOf(row.get("content_json")))) {
                return String.valueOf(row.get("content_json"));
            }
        }
        return null;
    }

    public boolean hasConfirmedPack(String college) {
        return ruleJson(college) != null;
    }

    private static boolean collegeMatches(String userCollege, String packCollege, String json) {
        if (userCollege == null || userCollege.isBlank()) return false;
        if (userCollege.equals(packCollege)) return true;
        if (json != null && json.contains("\"colleges\"") && json.contains(userCollege)) return true;
        return false;
    }

    public List<Map<String, Object>> listPacks(String college) {
        if (college == null || college.isBlank()) {
            return jdbc.queryForList("""
                    SELECT pack_id,college,grade_year,pack_code,title,enabled,IFNULL(confirmed,1) confirmed,
                    source_name,CHAR_LENGTH(content_json) bytes,create_time
                    FROM zhcp_rule_pack ORDER BY pack_id DESC
                    """);
        }
        return jdbc.queryForList("""
                SELECT pack_id,college,grade_year,pack_code,title,enabled,IFNULL(confirmed,1) confirmed,
                source_name,CHAR_LENGTH(content_json) bytes,create_time
                FROM zhcp_rule_pack WHERE college=? ORDER BY pack_id DESC
                """, college);
    }

    public Map<String, Object> pack(Long packId) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM zhcp_rule_pack WHERE pack_id=?", packId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long insertDraftPack(String college, String code, String title, String json, String sourceName) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO zhcp_rule_pack(college,grade_year,pack_code,title,content_json,enabled,confirmed,source_name)
                    VALUES(?,?,?,?,?,0,0,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, college);
            ps.setString(2, "2024");
            ps.setString(3, code);
            ps.setString(4, title);
            ps.setString(5, json);
            ps.setString(6, sourceName);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void updatePackJson(Long packId, String title, String json) {
        jdbc.update("UPDATE zhcp_rule_pack SET title=?, content_json=? WHERE pack_id=?", title, json, packId);
    }

    public void confirmPack(Long packId, String college) {
        jdbc.update("UPDATE zhcp_rule_pack SET enabled=0 WHERE college=? AND pack_id<>?", college, packId);
        jdbc.update("UPDATE zhcp_rule_pack SET enabled=1, confirmed=1 WHERE pack_id=?", packId);
    }

    public void saveRuleIfAbsent(String code, String college, String title, String json) {
        Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM zhcp_rule_pack WHERE pack_code=?", Integer.class, code);
        if (n != null && n > 0) {
            jdbc.update("UPDATE zhcp_rule_pack SET college=?, title=?, content_json=?, confirmed=1, enabled=1 WHERE pack_code=?",
                    college, title, json, code);
            return;
        }
        jdbc.update("""
                INSERT INTO zhcp_rule_pack(college,grade_year,pack_code,title,content_json,enabled,confirmed)
                VALUES(?,?,?,?,?,1,1)
                """, college, "2024", code, title, json);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}

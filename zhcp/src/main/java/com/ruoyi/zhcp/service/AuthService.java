package com.ruoyi.zhcp.service;

import com.ruoyi.zhcp.campus.CampusProfile;
import com.ruoyi.zhcp.campus.JwClient;
import com.ruoyi.zhcp.campus.XgClient;
import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.common.ServiceException;
import com.ruoyi.zhcp.config.ZhcpProperties;
import com.ruoyi.zhcp.crypto.AesCrypto;
import com.ruoyi.zhcp.security.TokenService;
import com.ruoyi.zhcp.store.Store;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {
    private final XgClient xg;
    private final JwClient jw;
    private final Store store;
    private final AesCrypto aes;
    private final TokenService tokens;
    private final ZhcpProperties props;

    public AuthService(XgClient xg, JwClient jw, Store store, AesCrypto aes, TokenService tokens, ZhcpProperties props) {
        this.xg = xg;
        this.jw = jw;
        this.store = store;
        this.aes = aes;
        this.tokens = tokens;
        this.props = props;
    }

    public Map<String, Object> login(String role, String account, String password) {
        if (account == null || password == null || account.isBlank() || password.isBlank()) {
            throw new ServiceException("请输入账号和密码");
        }
        if ("secretary".equals(role)) {
            return secretary(account.trim(), password);
        }
        if ("student".equals(role)) {
            return student(account.trim(), password);
        }
        throw new ServiceException("请选择团支书或学生身份");
    }

    private Map<String, Object> secretary(String account, String password) {
        CampusProfile p = xg.login(account, password);
        String college = nz(p.college, "未知学院");
        String className = nz(p.className, "未知班级");
        Long userId = store.upsertUser(account, nz(p.name, account), "secretary",
                nz(p.studentNo, account), college, className, account, aes.encrypt(password));
        Long classId = store.upsertClass(college, className, props.getDefaultSemester(), userId);
        for (CampusProfile.RosterRow row : p.roster) {
            if (row.studentNo != null && row.name != null) {
                store.upsertRoster(classId, row.studentNo, row.name);
            }
        }
        store.audit(userId, "secretary_login", String.valueOf(classId), "同步班级名单 " + p.roster.size() + " 人");
        return tokenPayload(store.findUserById(userId), p.roster.size());
    }

    private Map<String, Object> student(String account, String password) {
        CampusProfile p = jw.login(account, password);
        String no = nz(p.studentNo, account);
        String name = nz(p.name, account);
        String college = nz(p.college, "");
        String className = nz(p.className, "");
        Map<String, Object> roster = store.matchRoster(no, name, college, className);
        if (roster == null) {
            throw new ServiceException("团支书尚未使用本系统");
        }
        Long userId = store.upsertUser(no, name, "student", no, String.valueOf(roster.get("college")),
                String.valueOf(roster.get("class_name")), account, aes.encrypt(password));
        store.markRegistered(((Number) roster.get("roster_id")).longValue(), userId);
        store.ensureScore(((Number) roster.get("roster_id")).longValue(),
                ((Number) roster.get("class_id")).longValue(), props.getDefaultSemester());
        store.audit(userId, "student_login", String.valueOf(roster.get("roster_id")), "匹配注册");
        return tokenPayload(store.findUserById(userId), null);
    }

    private Map<String, Object> tokenPayload(LoginUser user, Integer rosterCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", tokens.create(user));
        data.put("user", userMap(user));
        if (rosterCount != null) data.put("rosterCount", rosterCount);
        data.put("adminUrl", props.getAdminUrl());
        return data;
    }

    public Map<String, Object> userMap(LoginUser user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", user.userId);
        m.put("userName", user.userName);
        m.put("nickName", user.nickName);
        m.put("role", user.role);
        m.put("studentNo", user.studentNo);
        m.put("college", user.college);
        m.put("className", user.className);
        return m;
    }

    private static String nz(String v, String d) {
        return v == null || v.isBlank() ? d : v.trim();
    }
}

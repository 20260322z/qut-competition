package com.ruoyi.zhcp.web;

import com.ruoyi.zhcp.campus.JwClient;
import com.ruoyi.zhcp.common.AjaxResult;
import com.ruoyi.zhcp.common.ServiceException;
import com.ruoyi.zhcp.crypto.AesCrypto;
import com.ruoyi.zhcp.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Read-only integration; reuses the current student's existing encrypted campus login. */
@RestController
public class AcademicSessionController {
    private final JdbcTemplate jdbc;
    private final AesCrypto crypto;
    private final JwClient client;
    public AcademicSessionController(JdbcTemplate jdbc, AesCrypto crypto, JwClient client) {
        this.jdbc=jdbc; this.crypto=crypto; this.client=client;
    }
    @PostMapping("/app/academic/my-transcript")
    public AjaxResult transcript(HttpServletRequest request, @RequestBody Map<String, Boolean> body) {
        var user=AuthInterceptor.current(request);
        if(!user.student()) throw new ServiceException(403,"请先用学生身份登录综测的教务系统");
        if(!Boolean.TRUE.equals(body.get("consent"))) throw new ServiceException("请确认读取本人成绩");
        var rows=jdbc.queryForList("SELECT campus_account,campus_password_enc FROM sys_user WHERE user_id=? AND role='student' AND student_no=?",user.userId,user.studentNo);
        if(rows.size()!=1) throw new ServiceException(401,"登录身份已失效，请重新登录综测");
        var row=rows.get(0);
        String account=Objects.toString(row.get("campus_account"),"");
        String enc=Objects.toString(row.get("campus_password_enc"),"");
        if(!account.equals(user.studentNo)||enc.isBlank()) throw new ServiceException("教务登录信息不完整，请重新登录综测");
        String password;
        try { password=crypto.decrypt(enc); }
        catch(Exception e) { throw new ServiceException("教务登录信息已失效，请重新登录综测"); }
        if(password==null||password.isBlank()) throw new ServiceException("教务登录信息已失效，请重新登录综测");
        List<Map<String,Object>> items=new ArrayList<>();
        for(var original:client.transcript(account,password)) {
            var clean=new LinkedHashMap<String,Object>(original);
            clean.remove("raw");
            items.add(clean);
        }
        return AjaxResult.success(Map.of("items",items,"source","教务系统","readOnly",true,
            "message","已读取本人全部学期成绩，综测分数与规则未改动"));
    }
}

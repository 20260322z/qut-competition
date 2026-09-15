package com.ruoyi.zhcp.web;

import com.ruoyi.zhcp.campus.JwClient;
import com.ruoyi.zhcp.common.*;
import com.ruoyi.zhcp.config.ZhcpProperties;
import com.ruoyi.zhcp.crypto.AesCrypto;
import com.ruoyi.zhcp.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.lang.reflect.Proxy;
import java.util.*;

/** Offline contract check, no network, database or real student information. Run with -ea. */
public class AcademicSessionCheck {
    static class Database extends JdbcTemplate {
        Map<String,Object> row; Object[] arguments;
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
            assert sql.contains("user_id=?")&&sql.contains("student_no=?")&&sql.contains("role='student'");
            arguments=args;return row==null?List.of():List.of(row);
        }
    }
    static class Campus extends JwClient {
        int calls;
        @Override public List<Map<String,Object>> transcript(String account,String password){
            assert account.equals("synthetic-student")&&password.equals("synthetic-password");calls++;
            return List.of(Map.of("course","合成课程","score","80","raw",Map.of("studentName","DO_NOT_RETURN")));
        }
    }
    static void denied(Runnable run){boolean rejected=false;try{run.run();}catch(ServiceException e){rejected=true;}assert rejected;}
    public static void main(String[] args){
        LoginUser user=new LoginUser();user.userId=42L;user.studentNo="synthetic-student";user.role="student";
        HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(AcademicSessionCheck.class.getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->m.getName().equals("getAttribute")&&AuthInterceptor.ATTR.equals(a[0])?user:null);
        var props=new ZhcpProperties();props.setAesKey("offline-test-key");var crypto=new AesCrypto(props);
        var db=new Database();var campus=new Campus();var controller=new AcademicSessionController(db,crypto,campus);
        db.row=Map.of("campus_account",user.studentNo,"campus_password_enc",crypto.encrypt("synthetic-password"));
        var result=controller.transcript(request,Map.of("consent",true));
        assert result.get("code").equals(200);assert db.arguments[0].equals(42L)&&db.arguments[1].equals(user.studentNo);
        assert !result.toString().contains("DO_NOT_RETURN")&&!result.toString().contains("synthetic-password");
        denied(()->controller.transcript(request,Map.of("consent",false)));
        user.role="secretary";denied(()->controller.transcript(request,Map.of("consent",true)));user.role="student";
        db.row=Map.of("campus_account","another-user","campus_password_enc",crypto.encrypt("synthetic-password"));denied(()->controller.transcript(request,Map.of("consent",true)));
        db.row=Map.of("campus_account",user.studentNo,"campus_password_enc","invalid");denied(()->controller.transcript(request,Map.of("consent",true)));
        db.row=null;denied(()->controller.transcript(request,Map.of("consent",true)));
        assert campus.calls==1;
        System.out.println("PASS: identity binding, student role, consent, credential validation, raw redaction, no campus call on denial");
    }
}

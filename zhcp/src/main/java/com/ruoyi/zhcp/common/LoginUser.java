package com.ruoyi.zhcp.common;

public class LoginUser {
    public Long userId;
    public String userName;
    public String nickName;
    public String role;
    public String studentNo;
    public String college;
    public String className;

    public boolean secretary() {
        return "secretary".equals(role);
    }

    public boolean student() {
        return "student".equals(role);
    }
}

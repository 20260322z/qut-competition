package com.ruoyi.zhcp.common;

import java.util.HashMap;

public class AjaxResult extends HashMap<String, Object> {
    public static AjaxResult success() {
        return success("操作成功", null);
    }

    public static AjaxResult success(Object data) {
        return success("操作成功", data);
    }

    public static AjaxResult success(String msg, Object data) {
        AjaxResult r = new AjaxResult();
        r.put("code", 200);
        r.put("msg", msg);
        if (data != null) {
            r.put("data", data);
        }
        return r;
    }

    public static AjaxResult error(String msg) {
        return error(500, msg);
    }

    public static AjaxResult error(int code, String msg) {
        AjaxResult r = new AjaxResult();
        r.put("code", code);
        r.put("msg", msg);
        return r;
    }

    public AjaxResult put(String key, Object value) {
        super.put(key, value);
        return this;
    }
}

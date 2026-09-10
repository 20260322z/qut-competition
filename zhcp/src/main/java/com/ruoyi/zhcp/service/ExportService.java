package com.ruoyi.zhcp.service;

import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.common.ServiceException;
import com.ruoyi.zhcp.store.Store;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@Service
public class ExportService {
    private final Store store;

    public ExportService(Store store) {
        this.store = store;
    }

    public byte[] exportClass(LoginUser user) throws Exception {
        Long classId = store.classIdOfSecretary(user.userId);
        if (classId == null) throw new ServiceException("尚未同步班级");
        List<Map<String, Object>> rows = store.classStudents(classId);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("综测加分库");
            String[] heads = {"序号", "姓名", "学号", "班级", "班内评价", "思想道德奖励", "思想道德扣分",
                    "专业基础分", "学业奖励", "学业扣分", "体育基础", "心理基础", "劳动分", "身心奖励", "身心扣分",
                    "社会工作", "特长", "科技创新", "总分", "专业排名", "综测排名", "是否注册", "签名确认"};
            Row h = sheet.createRow(0);
            for (int i = 0; i < heads.length; i++) h.createCell(i).setCellValue(heads[i]);
            int i = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(i);
                int c = 0;
                row.createCell(c++).setCellValue(i);
                row.createCell(c++).setCellValue(str(r.get("student_name")));
                row.createCell(c++).setCellValue(str(r.get("student_no")));
                row.createCell(c++).setCellValue(user.className);
                row.createCell(c++).setCellValue(num(r.get("moral_peer")));
                row.createCell(c++).setCellValue(num(r.get("moral_reward")));
                row.createCell(c++).setCellValue(num(r.get("moral_deduct")));
                row.createCell(c++).setCellValue(num(r.get("academic_base")));
                row.createCell(c++).setCellValue(num(r.get("academic_reward")));
                row.createCell(c++).setCellValue(num(r.get("academic_deduct")));
                row.createCell(c++).setCellValue(num(r.get("body_pe")));
                row.createCell(c++).setCellValue(num(r.get("body_mental")));
                row.createCell(c++).setCellValue(num(r.get("body_labor")));
                row.createCell(c++).setCellValue(num(r.get("body_reward")));
                row.createCell(c++).setCellValue(num(r.get("body_deduct")));
                row.createCell(c++).setCellValue(num(r.get("develop_work")));
                row.createCell(c++).setCellValue(num(r.get("develop_talent")));
                row.createCell(c++).setCellValue(num(r.get("develop_tech")));
                row.createCell(c++).setCellValue(num(r.get("total_score")));
                row.createCell(c++).setCellValue(num(r.get("major_rank")));
                row.createCell(c++).setCellValue(num(r.get("zhcp_rank")));
                row.createCell(c++).setCellValue(num(r.get("registered")) > 0 ? "已注册" : "未注册");
                row.createCell(c++).setCellValue(num(r.get("signed")) > 0 ? "已确认" : "");
                i++;
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0;
    }
}

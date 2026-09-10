package com.ruoyi.zhcp.campus;

import java.util.ArrayList;
import java.util.List;

public class CampusProfile {
    public String studentNo;
    public String name;
    public String college;
    public String className;
    public final List<RosterRow> roster = new ArrayList<>();

    public static class RosterRow {
        public String studentNo;
        public String name;

        public RosterRow(String studentNo, String name) {
            this.studentNo = studentNo;
            this.name = name;
        }
    }
}

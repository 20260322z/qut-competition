package com.ruoyi.zhcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhcp")
public class ZhcpProperties {
    private String jwtSecret;
    private int jwtTtlHours = 72;
    private String aesKey;
    private String uploadDir;
    private String deepseekApiKey;
    private String deepseekBase;
    private String visionModel;
    private String defaultSemester;
    private String adminUrl;

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public int getJwtTtlHours() { return jwtTtlHours; }
    public void setJwtTtlHours(int jwtTtlHours) { this.jwtTtlHours = jwtTtlHours; }
    public String getAesKey() { return aesKey; }
    public void setAesKey(String aesKey) { this.aesKey = aesKey; }
    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
    public String getDeepseekApiKey() { return deepseekApiKey; }
    public void setDeepseekApiKey(String deepseekApiKey) { this.deepseekApiKey = deepseekApiKey; }
    public String getDeepseekBase() { return deepseekBase; }
    public void setDeepseekBase(String deepseekBase) { this.deepseekBase = deepseekBase; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public String getDefaultSemester() { return defaultSemester; }
    public void setDefaultSemester(String defaultSemester) { this.defaultSemester = defaultSemester; }
    public String getAdminUrl() { return adminUrl; }
    public void setAdminUrl(String adminUrl) { this.adminUrl = adminUrl; }
}

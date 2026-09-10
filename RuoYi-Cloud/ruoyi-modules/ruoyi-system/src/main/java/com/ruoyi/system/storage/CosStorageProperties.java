package com.ruoyi.system.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.storage.cos")
public class CosStorageProperties
{
    private boolean enabled;
    private String region;
    private String bucket;
    private String secretId;
    private String secretKey;
    private String sessionToken;
    private int readUrlSeconds = 900;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getRegion() { return region; }
    public void setRegion(String value) { region = value; }
    public String getBucket() { return bucket; }
    public void setBucket(String value) { bucket = value; }
    public String getSecretId() { return secretId; }
    public void setSecretId(String value) { secretId = value; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String value) { secretKey = value; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String value) { sessionToken = value; }
    public int getReadUrlSeconds() { return readUrlSeconds; }
    public void setReadUrlSeconds(int value) { readUrlSeconds = value; }
}

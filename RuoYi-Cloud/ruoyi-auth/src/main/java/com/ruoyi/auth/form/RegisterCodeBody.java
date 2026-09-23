package com.ruoyi.auth.form;

/** Registration email-code request; code/uuid are consumed by the gateway captcha filter. */
public class RegisterCodeBody
{
    private String username;
    private String email;
    private String code;
    private String uuid;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
}

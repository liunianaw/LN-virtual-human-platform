package com.ruoyi.system.api.domain;

/** Public registration email-code request after gateway captcha validation. */
public class RegisterCodeRequest
{
    private String username;
    private String email;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}

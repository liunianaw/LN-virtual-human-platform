package com.ruoyi.system.api.domain;

/** Public registration payload. Roles and account state are intentionally absent. */
public class RegisterRequest
{
    private String username;
    private String email;
    private String password;
    private String emailCode;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmailCode() { return emailCode; }
    public void setEmailCode(String emailCode) { this.emailCode = emailCode; }
}

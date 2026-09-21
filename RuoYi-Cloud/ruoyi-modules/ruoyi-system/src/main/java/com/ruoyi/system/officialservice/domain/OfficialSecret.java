package com.ruoyi.system.officialservice.domain;

public class OfficialSecret
{
    private Long id; private byte[] ciphertext; private byte[] nonce; private byte[] authTag; private String keyVersion;
    private String algorithm; private String status;
    public Long getId() { return id; } public void setId(Long value) { id = value; }
    public byte[] getCiphertext() { return ciphertext; } public void setCiphertext(byte[] value) { ciphertext = value; }
    public byte[] getNonce() { return nonce; } public void setNonce(byte[] value) { nonce = value; }
    public byte[] getAuthTag() { return authTag; } public void setAuthTag(byte[] value) { authTag = value; }
    public String getKeyVersion() { return keyVersion; } public void setKeyVersion(String value) { keyVersion = value; }
    public String getAlgorithm() { return algorithm; } public void setAlgorithm(String value) { algorithm = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
}

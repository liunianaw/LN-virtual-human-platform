package com.ruoyi.system.officialservice.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.officialservice.domain.OfficialSecret;

/** AES-GCM storage boundary. The deployment key is supplied only by environment, never persisted. */
@Component
public class OfficialSecretCrypto
{
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private final SecureRandom random = new SecureRandom();

    public OfficialSecret encrypt(long id, String providerKey)
    {
        if (providerKey == null || providerKey.isBlank() || providerKey.length() > 4096) throw invalid();
        try
        {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGORITHM); cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] combined = cipher.doFinal(providerKey.getBytes(StandardCharsets.UTF_8));
            OfficialSecret secret = new OfficialSecret(); secret.setId(id); secret.setNonce(nonce);
            secret.setCiphertext(Arrays.copyOf(combined, combined.length - 16)); secret.setAuthTag(Arrays.copyOfRange(combined, combined.length - 16, combined.length));
            secret.setAlgorithm(ALGORITHM); secret.setKeyVersion(System.getenv().getOrDefault("LN_OFFICIAL_SERVICE_MASTER_KEY_VERSION", "v1")); secret.setStatus("ACTIVE");
            return secret;
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("官方凭证加密失败"); }
    }

    public String decrypt(OfficialSecret secret)
    {
        if (secret == null || !"ACTIVE".equals(secret.getStatus()) || !ALGORITHM.equals(secret.getAlgorithm())) throw new ServiceException("官方凭证不可用", 409);
        try
        {
            byte[] ciphertext = secret.getCiphertext(); byte[] tag = secret.getAuthTag(); byte[] nonce = secret.getNonce();
            if (ciphertext == null || tag == null || tag.length != 16 || nonce == null || nonce.length != 12) throw invalid();
            Cipher cipher = Cipher.getInstance(ALGORITHM); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            String value = new String(cipher.doFinal(join(ciphertext, tag)), StandardCharsets.UTF_8);
            if (value.isBlank() || value.length() > 4096) throw invalid(); return value;
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("官方凭证无法解密", 409); }
    }

    /** Safe request comparison for credential replacement; the plaintext never enters the idempotency table. */
    public byte[] keyedDigest(String value)
    {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(key()); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("官方凭证摘要失败"); }
    }

    private SecretKeySpec key()
    {
        String encoded = System.getenv("LN_OFFICIAL_SERVICE_MASTER_KEY");
        try
        {
            byte[] bytes = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (bytes.length != 32) throw invalid(); return new SecretKeySpec(bytes, "AES");
        }
        catch (IllegalArgumentException e) { throw invalid(); }
    }
    private static byte[] join(byte[] first, byte[] second) { byte[] all = Arrays.copyOf(first, first.length + second.length); System.arraycopy(second, 0, all, first.length, second.length); return all; }
    private static ServiceException invalid() { return new ServiceException("官方凭证主密钥未配置或格式无效", 503); }
}

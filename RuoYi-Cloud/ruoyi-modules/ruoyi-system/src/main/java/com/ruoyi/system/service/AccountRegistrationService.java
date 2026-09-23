package com.ruoyi.system.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.system.api.domain.RegisterCodeRequest;
import com.ruoyi.system.api.domain.RegisterRequest;
import com.ruoyi.system.api.domain.SysUser;

/** Owns the one-time registration code and the atomic developer-account creation. */
@Service
public class AccountRegistrationService
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final ISysUserService users;
    private final ObjectProvider<JavaMailSender> mailSenders;

    @Value("${platform.mail.from:}")
    private String senderAddress;

    public AccountRegistrationService(JdbcTemplate jdbc, ISysUserService users, ObjectProvider<JavaMailSender> mailSenders)
    {
        this.jdbc = jdbc;
        this.users = users;
        this.mailSenders = mailSenders;
    }

    @Transactional(rollbackFor = Exception.class)
    public void sendCode(RegisterCodeRequest request)
    {
        String username = required(request.getUsername(), "账号");
        String email = email(request.getEmail());
        requireAvailable(username, email);
        Integer recent = jdbc.queryForObject("select count(*) from p_email_token where email=? and purpose='VERIFY_EMAIL' and created_at>date_sub(utc_timestamp(3),interval 60 second)", Integer.class, email);
        if (recent != null && recent > 0) throw new ServiceException("验证码发送过于频繁，请稍后再试");

        JavaMailSender mailSender = mailSenders.getIfAvailable();
        if (mailSender == null || StringUtils.isBlank(senderAddress)) throw new ServiceException("邮件服务尚未配置");
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("insert into p_email_token (id,created_at,updated_at,user_id,email,purpose,token_hash,expires_at,failed_attempts) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),null,?,'VERIFY_EMAIL',?,date_add(utc_timestamp(3),interval 15 minute),0)", email, hash(code));
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(senderAddress);
        message.setTo(email);
        message.setSubject("LN 虚拟人平台邮箱验证码");
        message.setText("你的注册验证码是 " + code + "，15 分钟内有效。若非本人操作，请忽略此邮件。");
        mailSender.send(message);
    }

    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request)
    {
        String username = required(request.getUsername(), "账号");
        String email = email(request.getEmail());
        String code = required(request.getEmailCode(), "邮箱验证码");
        if (StringUtils.isBlank(request.getPassword())) throw new ServiceException("密码必须填写");
        requireAvailable(username, email);
        List<Map<String, Object>> tokens = jdbc.queryForList("select id,token_hash,expires_at,failed_attempts from p_email_token where email=? and purpose='VERIFY_EMAIL' and consumed_at is null order by created_at desc limit 1 for update", email);
        if (tokens.isEmpty()) throw new ServiceException("请先获取邮箱验证码");
        Map<String, Object> token = tokens.get(0);
        long tokenId = ((Number) token.get("id")).longValue();
        if (!expiresAt(token.get("expires_at")).isAfter(Instant.now())) throw new ServiceException("邮箱验证码已过期");
        if (((Number) token.get("failed_attempts")).intValue() >= MAX_ATTEMPTS) throw new ServiceException("邮箱验证码已失效，请重新获取");
        if (!MessageDigest.isEqual((byte[]) token.get("token_hash"), hash(code)))
        {
            jdbc.update("update p_email_token set failed_attempts=failed_attempts+1,updated_at=utc_timestamp(3) where id=?", tokenId);
            throw new ServiceException("邮箱验证码错误");
        }
        if (jdbc.update("update p_email_token set consumed_at=utc_timestamp(3),updated_at=utc_timestamp(3) where id=? and consumed_at is null", tokenId) != 1)
        {
            throw new ServiceException("邮箱验证码已使用，请重新获取");
        }
        SysUser user = new SysUser();
        user.setUserName(username);
        user.setNickName(username);
        user.setEmail(email);
        user.setPwdUpdateDate(DateUtils.getNowDate());
        user.setPassword(request.getPassword());
        if (!users.registerUser(user)) throw new ServiceException("注册失败，请稍后再试");
        jdbc.update("update sys_user set email_verified_at=utc_timestamp(3) where user_id=?", user.getUserId());
    }

    private void requireAvailable(String username, String email)
    {
        SysUser user = new SysUser();
        user.setUserName(username);
        user.setEmail(email);
        if (!users.checkUserNameUnique(user)) throw new ServiceException("注册账号已存在");
        if (!users.checkEmailUnique(user)) throw new ServiceException("邮箱已被注册");
    }

    private Instant expiresAt(Object value)
    {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        throw new ServiceException("邮箱验证码过期时间无效");
    }

    private static String required(String value, String label)
    {
        if (StringUtils.isBlank(value)) throw new ServiceException(label + "必须填写");
        return value.trim();
    }

    private static String email(String value)
    {
        String normalized = required(value, "邮箱").toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ServiceException("邮箱格式不正确");
        return normalized;
    }

    private static byte[] hash(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException("验证码摘要不可用", error); }
    }
}

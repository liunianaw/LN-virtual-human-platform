package com.ruoyi.system.account.domain;

import java.math.BigDecimal;
import com.ruoyi.common.core.exception.ServiceException;

/** Validated administrator settings, converted to the existing ledger units. */
public record RegistrationBenefits(int concurrency, long storageBytes, long pointsCent)
{
    public static final String CONCURRENCY_KEY = "platform.registration.initialConcurrency";
    public static final String STORAGE_MB_KEY = "platform.registration.initialStorageMb";
    public static final String POINTS_KEY = "platform.registration.initialPoints";

    public static boolean isBenefitKey(String key)
    {
        return CONCURRENCY_KEY.equals(key) || STORAGE_MB_KEY.equals(key) || POINTS_KEY.equals(key);
    }

    public static RegistrationBenefits from(String concurrency, String storageMb, String points)
    {
        return new RegistrationBenefits((int) validate(CONCURRENCY_KEY, concurrency),
            validate(STORAGE_MB_KEY, storageMb), validate(POINTS_KEY, points));
    }

    public static long validate(String key, String value)
    {
        if (!isBenefitKey(key)) return 0;
        String normalized = value == null ? "" : value.trim();
        try
        {
            if (POINTS_KEY.equals(key))
            {
                if (!normalized.matches("[0-9]+(?:\\.[0-9]{1,2})?")) throw new NumberFormatException();
                return new BigDecimal(normalized).movePointRight(2).longValueExact();
            }
            if (!normalized.matches("[0-9]+")) throw new NumberFormatException();
            long amount = Long.parseLong(normalized);
            if (STORAGE_MB_KEY.equals(key)) return Math.multiplyExact(amount, 1024L * 1024);
            if (amount > 10000) throw new NumberFormatException();
            return amount;
        }
        catch (ArithmeticException | NumberFormatException error)
        {
            throw new ServiceException("新用户注册赠送参数无效：" + key + "；并发为 0～10000 整数，存储为非负整数 MB，积分为非负数且最多两位小数");
        }
    }
}

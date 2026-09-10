package com.ruoyi.system.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.exception.ServiceException;

@Service
public class AccountIconService
{
    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_DIMENSION = 2048;
    private final ObjectProvider<ObjectStorage> providers;

    public AccountIconService(ObjectProvider<ObjectStorage> providers) { this.providers = providers; }

    public String upload(Long accountId, MultipartFile file)
    {
        ObjectStorage storage = requireStorage();
        if (accountId == null || accountId <= 0 || file == null || file.isEmpty() || file.getSize() > MAX_BYTES)
            throw new ServiceException("头像不能为空，且大小不能超过 5 MB");
        byte[] png;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file.getBytes())))
        {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ServiceException("头像必须为真实 PNG 或 JPEG 图片");
            var reader = readers.next();
            try
            {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg"))
                    throw new ServiceException("头像仅支持 PNG 或 JPEG");
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
                    throw new ServiceException("头像尺寸不能超过 2048 × 2048");
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(reader.read(0), "png", output))
                    throw new ServiceException("头像转换失败");
                png = output.toByteArray();
                if (png.length > MAX_BYTES) throw new ServiceException("转换后的头像不能超过 5 MB");
            }
            finally { reader.dispose(); }
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("无法读取头像图片"); }
        String key = "account-icons/" + accountId + "/" + UUID.randomUUID().toString().replace("-", "") + ".png";
        storage.put(key, png, "image/png");
        return key;
    }

    public String readUrl(Long accountId, String key)
    {
        if (!owns(accountId, key)) return "";
        ObjectStorage storage = providers.getIfAvailable();
        return storage == null ? "" : storage.readUrl(key);
    }

    public void delete(Long accountId, String key)
    {
        if (!owns(accountId, key)) throw new ServiceException("无权删除此头像");
        requireStorage().delete(key);
    }

    private boolean owns(Long accountId, String key)
    {
        return accountId != null && accountId > 0 && key != null
            && key.matches("account-icons/" + accountId + "/[a-f0-9]{32}\\.png");
    }

    private ObjectStorage requireStorage()
    {
        ObjectStorage storage = providers.getIfAvailable();
        if (storage == null) throw new ServiceException("云对象存储尚未配置");
        return storage;
    }
}

package com.ruoyi.system.storage;

import com.qcloud.cos.COS;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CosStorageProperties.class)
@ConditionalOnProperty(prefix = "platform.storage.cos", name = "enabled", havingValue = "true")
public class CosStorageConfiguration
{
    @Bean(destroyMethod = "shutdown")
    public COS cosClient(CosStorageProperties config)
    {
        if (!StringUtils.hasText(config.getRegion()) || !StringUtils.hasText(config.getBucket())
            || !StringUtils.hasText(config.getSecretId()) || !StringUtils.hasText(config.getSecretKey()))
            throw new IllegalStateException("COS 已启用，必须配置 region、bucket 和环境凭证");
        if (config.getReadUrlSeconds() < 60 || config.getReadUrlSeconds() > 3600)
            throw new IllegalStateException("COS read-url-seconds 必须在 60 到 3600 之间");
        COSCredentials credentials = StringUtils.hasText(config.getSessionToken())
            ? new BasicSessionCredentials(config.getSecretId(), config.getSecretKey(), config.getSessionToken())
            : new BasicCOSCredentials(config.getSecretId(), config.getSecretKey());
        var client = new ClientConfig(new Region(config.getRegion()));
        client.setHttpProtocol(HttpProtocol.https);
        client.setConnectionTimeout(10000);
        client.setSocketTimeout(30000);
        return new COSClient(credentials, client);
    }

    @Bean
    public ObjectStorage objectStorage(COS client, CosStorageProperties config)
    {
        return new CosObjectStorage(client, config);
    }
}

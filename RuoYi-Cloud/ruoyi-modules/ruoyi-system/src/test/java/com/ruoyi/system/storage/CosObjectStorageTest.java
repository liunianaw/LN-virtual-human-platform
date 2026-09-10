package com.ruoyi.system.storage;

import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import com.qcloud.cos.COS;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.ObjectMetadata;
import com.ruoyi.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CosObjectStorageTest
{
    @Test
    void adapterUsesConfiguredBucketAndPrivateObjectKeys() throws Exception
    {
        COS client = mock(COS.class);
        var config = new CosStorageProperties();
        config.setBucket("test-bucket-123");
        config.setReadUrlSeconds(900);
        var storage = new CosObjectStorage(client, config);
        doAnswer(call -> {
            assertArrayEquals(new byte[]{1, 2}, ((InputStream) call.getArgument(2)).readAllBytes());
            ObjectMetadata metadata = call.getArgument(3);
            assertEquals(2, metadata.getContentLength());
            assertEquals("image/png", metadata.getContentType());
            return null;
        }).when(client).putObject(eq("test-bucket-123"), eq("icons/test.png"),
            any(InputStream.class), any(ObjectMetadata.class));
        storage.put("icons/test.png", new byte[]{1, 2}, "image/png");
        when(client.generatePresignedUrl(eq("test-bucket-123"), eq("icons/test.png"),
            any(Date.class), eq(HttpMethodName.GET))).thenAnswer(call -> {
                long seconds = (((Date) call.getArgument(2)).getTime() - System.currentTimeMillis()) / 1000;
                assertTrue(seconds >= 898 && seconds <= 900);
                return new URL("https://storage.example/icons/test.png?signature=test");
            });
        assertTrue(storage.readUrl("icons/test.png").startsWith("https://"));
        storage.delete("icons/test.png");
        verify(client).deleteObject("test-bucket-123", "icons/test.png");
        verify(client).putObject(eq("test-bucket-123"), eq("icons/test.png"),
            any(InputStream.class), any(ObjectMetadata.class));
    }

    @Test
    void providerFailureDoesNotExposeItsRawMessage()
    {
        COS client = mock(COS.class);
        var storage = new CosObjectStorage(client, new CosStorageProperties());
        doThrow(new IllegalStateException("secret-provider-response")).when(client).deleteObject((String) null, "key");
        var error = assertThrows(ServiceException.class, () -> storage.delete("key"));
        assertFalse(error.getMessage().contains("secret-provider-response"));
        assertNull(error.getCause());
    }
}

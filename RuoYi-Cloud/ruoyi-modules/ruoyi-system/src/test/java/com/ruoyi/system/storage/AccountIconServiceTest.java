package com.ruoyi.system.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class AccountIconServiceTest
{
    private final MemoryStorage storage = new MemoryStorage();
    private final AccountIconService service = new AccountIconService(
        new StaticListableBeanFactory(Map.of("storage", storage)).getBeanProvider(ObjectStorage.class));

    @Test
    void uploadedIconIsOwnedByAccountAndReencodedAsPng() throws Exception
    {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpg", output);
        String key = service.upload(12L, new MockMultipartFile("avatarfile", "../photo.jpg", "text/html", output.toByteArray()));
        assertTrue(key.matches("account-icons/12/[a-f0-9]{32}\\.png"));
        assertEquals("image/png", storage.type);
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(storage.objects.get(key)));
        assertEquals(2, decoded.getWidth());
        assertEquals(3, decoded.getHeight());
        assertTrue(service.readUrl(12L, key).startsWith("https://storage.example/"));
    }

    @Test
    void disguisedTextEmptyAndOversizedUploadsNeverReachStorage()
    {
        for (byte[] bytes : new byte[][] { "<script>alert(1)</script>".getBytes(), new byte[0], new byte[5 * 1024 * 1024 + 1] })
            assertThrows(RuntimeException.class, () -> service.upload(12L,
                new MockMultipartFile("avatarfile", "photo.png", "image/png", bytes)));
        assertTrue(storage.objects.isEmpty());
    }

    @Test
    void cannotSignOrDeleteAnotherAccountsObjectOrArbitraryUrl()
    {
        String ownKey = "account-icons/12/0123456789abcdef0123456789abcdef.png";
        storage.objects.put(ownKey, new byte[]{1});
        for (String key : new String[]{ ownKey, "https://foreign.example/file", "account-icons/13/../12/file.png" })
        {
            assertEquals("", service.readUrl(13L, key));
            assertThrows(RuntimeException.class, () -> service.delete(13L, key));
        }
        assertEquals(1, storage.objects.size());
        service.delete(12L, ownKey);
        assertTrue(storage.objects.isEmpty());
    }

    @Test
    void disabledStorageAllowsDefaultAvatarButRejectsUpload()
    {
        var disabled = new AccountIconService(new StaticListableBeanFactory().getBeanProvider(ObjectStorage.class));
        assertEquals("", disabled.readUrl(12L, null));
        assertThrows(RuntimeException.class, () -> disabled.upload(12L,
            new MockMultipartFile("avatarfile", new byte[0])));
    }

    private static class MemoryStorage implements ObjectStorage
    {
        final Map<String, byte[]> objects = new HashMap<>();
        String type;
        public void put(String key, byte[] bytes, String contentType) { objects.put(key, bytes); type = contentType; }
        public String readUrl(String key) { return "https://storage.example/" + key + "?temporary-signature"; }
        public void delete(String key) { objects.remove(key); }
    }
}

package cn.personal.recorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Authenticated text envelope, separate from the audio protocol and AAD domain. */
final class TextCrypto {
    static final int LIMIT = 1024 * 1024;
    static byte[] encrypt(byte[] text, SecretKey key, String name) throws Exception {
        if (text.length > LIMIT) throw new IOException("文字记录超过 1 MB，请拆分处理");
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        c.updateAAD(("text-v1:" + name).getBytes(StandardCharsets.UTF_8)); byte[] encrypted = c.doFinal(text);
        byte[] out = new byte[16 + encrypted.length]; System.arraycopy(new byte[]{'E','T','0','1'}, 0, out, 0, 4);
        System.arraycopy(nonce, 0, out, 4, 12); System.arraycopy(encrypted, 0, out, 16, encrypted.length); return out;
    }
    static byte[] decrypt(byte[] blob, SecretKey key, String name) throws Exception {
        if (blob.length < 32 || blob.length > LIMIT + 32 || blob[0] != 'E' || blob[1] != 'T' || blob[2] != '0' || blob[3] != '1')
            throw new IOException("文字密文格式无效");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 4, 12));
        c.updateAAD(("text-v1:" + name).getBytes(StandardCharsets.UTF_8)); return c.doFinal(blob, 16, blob.length - 16);
    }
}

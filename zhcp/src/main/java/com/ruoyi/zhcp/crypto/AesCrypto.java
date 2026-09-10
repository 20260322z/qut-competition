package com.ruoyi.zhcp.crypto;

import com.ruoyi.zhcp.config.ZhcpProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class AesCrypto {
    private final byte[] key;

    public AesCrypto(ZhcpProperties props) {
        byte[] raw = props.getAesKey().getBytes(StandardCharsets.UTF_8);
        this.key = new byte[16];
        System.arraycopy(raw, 0, key, 0, Math.min(raw.length, 16));
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
            return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("加密失败");
        }
    }

    public String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) {
            return "";
        }
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
            return new String(c.doFinal(Base64.getDecoder().decode(enc)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}

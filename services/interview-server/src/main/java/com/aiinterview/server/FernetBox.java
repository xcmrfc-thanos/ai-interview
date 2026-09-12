package com.aiinterview.server;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Fernet 对称加密盒：与 Python cryptography.Fernet 完全互操作。
 * 密钥派生：base64url(sha256(secret)) 解码为 32 字节后，前 16 字节为 HMAC 签名密钥、
 * 后 16 字节为 AES-128 加密密钥（与 cryptography.Fernet 的 _get_keys 分配一致）。
 * token 格式：base64url(0x80 || 8B 大端时间戳 || 16B IV || AES-128-CBC+PKCS7 密文 || 32B HMAC-SHA256)。
 */
public final class FernetBox {

    private static final int IV_LENGTH = 16;
    private static final int HMAC_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] encryptionKey;
    private final byte[] signingKey;

    public FernetBox(String secret) {
        byte[] derived = sha256(secret.getBytes(StandardCharsets.UTF_8));
        signingKey = new byte[16];
        encryptionKey = new byte[16];
        System.arraycopy(derived, 0, signingKey, 0, 16);
        System.arraycopy(derived, 16, encryptionKey, 0, 16);
    }

    /** 从 env 解析密钥：CONFIG_ENCRYPTION_KEY 优先，缺失回退 APP_SECRET_KEY；均缺失返回 null。 */
    public static FernetBox fromEnv() {
        String secret = firstNonBlank(System.getenv("CONFIG_ENCRYPTION_KEY"),
            System.getenv("APP_SECRET_KEY"));
        return secret == null ? null : new FernetBox(secret);
    }

    /** 加密明文为 Fernet token；异常返回 null。 */
    public String encrypt(String plain) {
        try {
            byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            byte[] ciphertext = aesCbc(true, plainBytes, iv);
            byte[] payload = new byte[1 + 8 + IV_LENGTH + ciphertext.length];
            payload[0] = (byte) 0x80;
            long timestamp = System.currentTimeMillis() / 1000;
            for (int i = 0; i < 8; i++) {
                payload[1 + i] = (byte) (timestamp >>> (8 * (7 - i)));
            }
            System.arraycopy(iv, 0, payload, 9, IV_LENGTH);
            System.arraycopy(ciphertext, 0, payload, 9 + IV_LENGTH, ciphertext.length);
            byte[] hmac = hmacSha256(payload);
            byte[] tokenBytes = new byte[payload.length + HMAC_LENGTH];
            System.arraycopy(payload, 0, tokenBytes, 0, payload.length);
            System.arraycopy(hmac, 0, tokenBytes, payload.length, HMAC_LENGTH);
            return Base64.getUrlEncoder().encodeToString(tokenBytes);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解密 Fernet token；token 空、密钥缺失或不匹配（密钥轮换/损坏）时返回 null，不抛异常。 */
    public String decrypt(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token);
            if (tokenBytes.length < 1 + 8 + IV_LENGTH + HMAC_LENGTH || tokenBytes[0] != (byte) 0x80) {
                return null;
            }
            int payloadLength = tokenBytes.length - HMAC_LENGTH;
            byte[] payload = Arrays.copyOfRange(tokenBytes, 0, payloadLength);
            byte[] hmac = Arrays.copyOfRange(tokenBytes, payloadLength, tokenBytes.length);
            if (!MessageDigest.isEqual(hmac, hmacSha256(payload))) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(payload, 9, 9 + IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(payload, 9 + IV_LENGTH, payloadLength);
            byte[] plainBytes = aesCbc(false, ciphertext, iv);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 掩码展示：保留末 4 位；长度 < 8 返回 ****；空返回空串（与 Python mask_secret 对齐）。 */
    public static String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        String tail = plain.length() >= 8 ? plain.substring(plain.length() - 4) : "****";
        return "****" + tail;
    }

    private byte[] aesCbc(boolean encrypt, byte[] input, byte[] iv) throws Exception {
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(new AESEngine()), new PKCS7Padding());
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(encryptionKey), iv));
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        int len = cipher.processBytes(input, 0, input.length, output, 0);
        len += cipher.doFinal(output, len);
        return len == output.length ? output : Arrays.copyOf(output, len);
    }

    private byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        return (b != null && !b.trim().isEmpty()) ? b.trim() : null;
    }
}

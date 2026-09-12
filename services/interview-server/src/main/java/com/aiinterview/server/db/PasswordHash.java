package com.aiinterview.server.db;

import org.bouncycastle.crypto.generators.SCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * werkzeug 兼容的密码哈希：
 * <ul>
 *   <li>验证：scrypt:32768:8:1$saltB64$hashB64（现有 users 表格式）</li>
 *   <li>验证：明文/历史弱哈希（password_needs_upgrade 语义，登录成功后升级）</li>
 *   <li>生成：与 werkzeug generate_password_hash("scrypt") 相同格式</li>
 * </ul>
 */
public final class PasswordHash {

    private static final int SCRYPT_N = 32768;
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;
    private static final int SCRYPT_LEN = 32;
    private static final String PREFIX = "scrypt:" + SCRYPT_N + ":" + SCRYPT_R + ":" + SCRYPT_P + "$";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHash() {
    }

    /** 是否弱哈希（明文或非 scrypt/pbkdf2 前缀）。 */
    public static boolean needsUpgrade(String stored) {
        String s = stored == null ? "" : stored;
        return !(s.startsWith("scrypt:") || s.startsWith("pbkdf2:"));
    }

    /** 验证密码：兼容 scrypt 与明文/弱哈希。 */
    public static boolean verify(String stored, String provided) {
        if (stored == null || provided == null) {
            return false;
        }
        if (needsUpgrade(stored)) {
            return constantTimeEquals(stored, provided);
        }
        if (stored.startsWith("scrypt:")) {
            return verifyScrypt(stored, provided);
        }
        return constantTimeEquals(stored, provided);
    }

    /** 生成 werkzeug 兼容 scrypt 哈希。 */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = SCrypt.generate(
            password.getBytes(StandardCharsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, SCRYPT_LEN);
        return PREFIX + Base64.getEncoder().encodeToString(salt)
            + "$" + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean verifyScrypt(String stored, String provided) {
        String body = stored.substring("scrypt:".length());
        int sep = body.indexOf('$');
        if (sep <= 0) {
            return false;
        }
        String params = body.substring(0, sep);
        String saltHash = body.substring(sep + 1);
        int sep2 = saltHash.indexOf('$');
        if (sep2 <= 0) {
            return false;
        }
        String[] parts = params.split(":");
        if (parts.length != 3) {
            return false;
        }
        try {
            int n = Integer.parseInt(parts[0]);
            int r = Integer.parseInt(parts[1]);
            int p = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(saltHash.substring(0, sep2));
            byte[] expected = Base64.getDecoder().decode(saltHash.substring(sep2 + 1));
            byte[] actual = SCrypt.generate(
                provided.getBytes(StandardCharsets.UTF_8), salt, n, r, p, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

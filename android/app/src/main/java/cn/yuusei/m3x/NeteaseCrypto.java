package cn.yuusei.m3x;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** Request envelopes used by the NetEase Cloud Music web (weapi) and desktop (eapi) clients. */
final class NeteaseCrypto {
    private static final String PRESET = "0CoJUm6Qyw8W8jud", IV = "0102030405060708", EAPI = "e82ckenh8dichen8";
    private static final BigInteger MODULUS = new BigInteger("00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7", 16);
    private static final BigInteger EXPONENT = new BigInteger("010001", 16);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private NeteaseCrypto() {}

    static String aesCbc(String text, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    static String rsa(String secret) {
        String reversed = new StringBuilder(secret).reverse().toString();
        BigInteger value = new BigInteger(1, reversed.getBytes(StandardCharsets.UTF_8));
        String hex = value.modPow(EXPONENT, MODULUS).toString(16);
        StringBuilder padded = new StringBuilder();
        for (int i = hex.length(); i < 256; i++) padded.append('0');
        return padded.append(hex).toString();
    }

    static String secret() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 16; i++) value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return value.toString();
    }

    /** Returns {params, encSecKey}. */
    static String[] weapi(String json) throws Exception { return weapi(json, secret()); }
    static String[] weapi(String json, String secret) throws Exception {
        return new String[] { aesCbc(aesCbc(json, PRESET), secret), rsa(secret) };
    }

    static String md5(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    /** eapi params for a path such as "/api/song/enhance/player/url/v1". */
    static String eapi(String path, String json) throws Exception {
        String digest = md5("nobody" + path + "use" + json + "md5forencrypt");
        String data = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest;
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(EAPI.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] bytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02X", b));
        return out.toString();
    }
}

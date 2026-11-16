package cn.yuusei.m3x;

import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

/** Read-only TLS diagnostics: captures the presented chain, then delegates validation unchanged. */
public final class LyricsTlsDeviceTest extends InstrumentationTestCase {
    public void testDiagnoseProviderTls() throws Exception {
        JSONArray report = new JSONArray();
        for (String host : new String[]{"c.y.qq.com", "u.y.qq.com", "music.163.com"}) {
            JSONObject row = new JSONObject().put("host", host); report.put(row);
            JSONArray certificates = new JSONArray(); row.put("chain", certificates);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore)null);
            X509TrustManager system = (X509TrustManager)factory.getTrustManagers()[0];
            X509TrustManager diagnostic = new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { system.checkClientTrusted(chain, auth); }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        for (X509Certificate cert : chain) certificates.put(new JSONObject()
                            .put("subject", cert.getSubjectX500Principal().getName())
                            .put("issuer", cert.getIssuerX500Principal().getName())
                            .put("sha256", android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()), android.util.Base64.NO_WRAP))
                            .put("pem", "-----BEGIN CERTIFICATE-----\n" + android.util.Base64.encodeToString(cert.getEncoded(), android.util.Base64.NO_WRAP) + "\n-----END CERTIFICATE-----\n"));
                    } catch (Exception error) { throw new CertificateException(error); }
                    system.checkServerTrusted(chain, auth);
                }
            };
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                row.put("addresses", java.util.Arrays.toString(addresses));
                SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{diagnostic}, null);
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .sslSocketFactory(context.getSocketFactory(), diagnostic)
                    .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build();
                try (okhttp3.Response response = client.newCall(new okhttp3.Request.Builder().url("https://" + host + "/").build()).execute()) {
                    row.put("trusted", true).put("status", response.code());
                }
            } catch (Exception error) { row.put("error", error.toString()); }
            android.util.Log.i("M3X.TlsTest", row.toString());
        }
        File root = getInstrumentation().getTargetContext().getExternalFilesDir(null);
        try (FileOutputStream out = new FileOutputStream(new File(root, "lyrics-tls-diagnostic.json"))) { out.write(report.toString(2).getBytes("UTF-8")); }
        assertEquals(3, report.length());
    }
}

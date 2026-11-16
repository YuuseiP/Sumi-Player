package cn.yuusei.m3x;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;

/**
 * In-app browser for the official NetEase Cloud Music login page. The user signs in on NetEase's own page;
 * once a MUSIC_U cookie appears it is returned to the caller and the browser's cookies are wiped, so the
 * credential only remains in the keystore-encrypted settings.
 */
public final class NeteaseLoginActivity extends Activity {
    static final String EXTRA_COOKIE = "cookie";
    private static final String LOGIN = "https://music.163.com/m/login";
    private static final String[] COOKIE_HOSTS = {"https://music.163.com", "https://y.music.163.com", "https://interface.music.163.com"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private boolean finished;
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (!check()) handler.postDelayed(this, 1000); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        // Start from a clean session so an earlier account never signs in silently.
        cookies.removeAllCookies(null);
        cookies.flush();

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(245, 242, 235));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12), dp(6), dp(6), dp(6));
        status = new TextView(this); status.setText("请在网易云官方页面登录，完成后自动返回"); status.setTextColor(Color.rgb(40, 40, 36)); status.setTextSize(13);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button cancel = new Button(this); cancel.setText("取消"); cancel.setOnClickListener(v -> finish());
        bar.addView(cancel);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl(); String scheme = uri.getScheme();
                // Keep web pages inside; app-scheme links (orpheus:// etc.) cannot open here.
                if ("https".equals(scheme) || "http".equals(scheme)) return false;
                Toast.makeText(NeteaseLoginActivity.this, "请在此页面内完成登录", Toast.LENGTH_SHORT).show();
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) { check(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); progress.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE); }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        web.loadUrl(LOGIN);
        handler.postDelayed(poll, 1000);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Returns true once the login cookie has been captured. */
    private boolean check() {
        if (finished) return true;
        CookieManager manager = CookieManager.getInstance();
        StringBuilder all = new StringBuilder();
        String music = "";
        for (String host : COOKIE_HOSTS) {
            String value = manager.getCookie(host);
            if (value == null) continue;
            for (String part : value.split(";")) {
                String pair = part.trim(); int at = pair.indexOf('=');
                if (at <= 0) continue;
                String name = pair.substring(0, at);
                if (all.indexOf(name + "=") == 0 || all.indexOf("; " + name + "=") >= 0) continue;
                if (all.length() > 0) all.append("; ");
                all.append(pair);
                if (name.equals("MUSIC_U")) music = pair.substring(at + 1);
            }
        }
        if (music.length() < 16) return false;
        finished = true;
        status.setText("登录成功，正在返回…");
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_COOKIE, all.toString()));
        finish();
        return true;
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        // The cookie now lives only in the encrypted settings (or nowhere, if cancelled).
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        // WebStorage is shared with the player UI (its localStorage), so only this page's cache is cleared.
        if (web != null) web.clearCache(true);
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
}

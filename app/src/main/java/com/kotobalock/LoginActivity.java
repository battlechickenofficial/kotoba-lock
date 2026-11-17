package com.kotobalock;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Opens kotobaweb.com so you can "Login with Discord". The session cookie it sets
 * (valid ~1 year) is then reused by Deck.sync() to download the deck.
 */
public class LoginActivity extends Activity {
    private WebView web;
    private boolean syncing, done;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                // Block discord:// / intent:// app hand-offs; stay inside the WebView.
                return scheme == null || !(scheme.equals("http") || scheme.equals("https"));
            }
            @Override public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
                Uri u = Uri.parse(url);
                if ("kotobaweb.com".equals(u.getHost()) && url.contains("/dashboard")) trySync(false);
            }
        });
        String[] p = Prefs.parseDeckUrl(Prefs.deckUrl(this));
        web.loadUrl(p != null ? Prefs.deckUrl(this) : "https://kotobaweb.com/dashboard");
        Toast.makeText(this, "Tap \"Login with Discord\", sign in, then wait — it closes by itself", Toast.LENGTH_LONG).show();
    }

    private void trySync(boolean userAsked) {
        if (syncing || done) return;
        syncing = true;
        new Thread(() -> {
            Deck.SyncResult r = Deck.sync(getApplicationContext());
            new Handler(Looper.getMainLooper()).post(() -> {
                syncing = false;
                if (r.ok) {
                    done = true;
                    Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
                    finish();
                } else if (userAsked || !r.needsLogin) {
                    Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Sync now").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { trySync(true); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}

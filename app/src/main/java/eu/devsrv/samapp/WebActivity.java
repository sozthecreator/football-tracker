package eu.devsrv.samapp;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** In-app browser for a match page; Back goes through the page history, then closes. */
public class WebActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); // the live score card is script-driven
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient()); // keep links inside the app
        FrameLayout root = new FrameLayout(this);
        root.addView(web);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setOnApplyWindowInsetsListener((v, insets) -> { // targetSdk 37 draws edge-to-edge
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        web.loadUrl(getIntent().getStringExtra("url"));
    }

    private void back() {
        if (web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { // Android 10-12; 13+ uses the callback above
        back();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}

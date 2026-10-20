package com.dsh.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String TAG = "DshMobile";
    private static final String PREFS = "dsh_mobile";
    private static final String KEY_URL = "pair_url";
    private static final int REQ_FILE = 1001;
    private static final int REQ_SCAN = 1002;
    private static final int REQ_STORAGE = 1003;

    private static final int C_BG = 0xFF0B0F14;
    private static final int C_PANEL = 0xFF111827;
    private static final int C_FIELD = 0xFF1B2433;
    private static final int C_TEXT = 0xFFE5E7EB;
    private static final int C_MUTED = 0xFF94A3B8;
    private static final int C_CYAN = 0xFF22D3EE;
    private static final int C_GREEN = 0xFF34D399;
    private static final int C_RED = 0xFFF87171;

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private TextView statusView;
    private ProgressBar progress;
    private String currentUrl;
    private int textZoom = 100;
    private ValueCallback<Uri[]> fileCallback;
    private long lastBackPress;
    private boolean showingError;
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMime;
    private long pendingDownloadLength;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);
        setContentView(root);

        String fromIntent = extractPairUrl(getIntent());
        if (fromIntent != null) {
            prefs.edit().putString(KEY_URL, fromIntent).apply();
            showWeb(fromIntent);
        } else {
            String saved = prefs.getString(KEY_URL, null);
            if (saved != null) showWeb(saved);
            else showSetup(null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = extractPairUrl(intent);
        if (url != null) {
            prefs.edit().putString(KEY_URL, url).apply();
            showWeb(url);
        }
    }

    /* ------------------------------------------------------------ setup UI */

    private void showSetup(String prefill) {
        destroyWeb();
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(36), dp(24), dp(32));
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView brand = text("DSH 手机控制", 28, C_TEXT, true);
        box.addView(brand);

        TextView tagline = text("电脑执行 · 手机查看", 14, C_CYAN, false);
        tagline.setPadding(0, dp(6), 0, dp(24));
        box.addView(tagline);

        TextView step1 = text("1. 电脑上保持 DSH 桌面端打开", 14, C_TEXT, false);
        TextView step2 = text("2. 运行 start-mobile.bat，终端会显示二维码", 14, C_TEXT, false);
        TextView step3 = text("3. 扫码，或把终端里的配对链接粘贴到下面", 14, C_TEXT, false);
        for (TextView v : new TextView[]{step1, step2, step3}) {
            v.setPadding(0, dp(4), 0, dp(4));
            v.setLineSpacing(dp(4), 1f);
            box.addView(v);
        }

        TextView label = text("配对链接", 12, C_MUTED, false);
        label.setPadding(0, dp(24), 0, dp(8));
        box.addView(label);

        final EditText input = new EditText(this);
        input.setHint("https://dsh.example.com/pair/… 或 dshmobile://pair?u=…");
        input.setHintTextColor(0xFF64748B);
        input.setTextColor(C_TEXT);
        input.setTextSize(14);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        input.setBackground(rounded(C_FIELD, dp(12)));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (prefill != null) input.setText(prefill);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView error = text("", 13, C_RED, false);
        error.setPadding(0, dp(8), 0, 0);
        box.addView(error);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(16), 0, 0);
        box.addView(row);

        TextView connect = pill("连接", C_CYAN, 0xFF07111F, false);
        row.addView(connect, new LinearLayout.LayoutParams(0,
                dp(50), 1.2f));

        TextView scan = pill("扫码", 0x00000000, C_CYAN, true);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        scanLp.leftMargin = dp(10);
        row.addView(scan, scanLp);

        TextView paste = pill("粘贴", 0x00000000, C_MUTED, true);
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(0, dp(50), 0.8f);
        pasteLp.leftMargin = dp(10);
        row.addView(paste, pasteLp);

        connect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = normalize(input.getText().toString());
                if (url == null) {
                    error.setText("链接格式不对：需要包含 /pair/ 的配对链接，或 dshmobile://pair?u=…");
                    return;
                }
                prefs.edit().putString(KEY_URL, url).apply();
                showWeb(url);
            }
        });

        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQ_SCAN);
            }
        });

        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) {
                    error.setText("剪贴板是空的");
                    return;
                }
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;
                CharSequence s = clip.getItemAt(0).coerceToText(MainActivity.this);
                if (s != null) input.setText(s.toString().trim());
            }
        });

        TextView note = text("默认在同一局域网使用；公网请走 HTTPS 反代/隧道。配对链接等同于密码，泄露后在电脑上重启桥接并加 --reset-pair。",
                12, 0xFF64748B, false);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(0, dp(28), 0, 0);
        box.addView(note);

        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /* -------------------------------------------------------------- web UI */

    private void showWeb(String url) {
        destroyWeb();
        root.removeAllViews();
        currentUrl = url;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(C_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(4), 0);
        bar.setBackgroundColor(C_PANEL);

        TextView title = text("DSH 手机控制", 16, C_TEXT, true);
        bar.addView(title);

        statusView = text("● 连接中", 11, C_MUTED, false);
        statusView.setPadding(dp(10), 0, 0, 0);
        bar.addView(statusView);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView reload = text("⟳", 22, C_CYAN, false);
        reload.setPadding(dp(12), dp(8), dp(12), dp(8));
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (web != null) web.reload();
            }
        });
        bar.addView(reload);

        final TextView menu = text("⋮", 24, C_TEXT, false);
        menu.setPadding(dp(10), dp(4), dp(12), dp(8));
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(v); }
        });
        bar.addView(menu);

        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        column.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(this);
        web.setBackgroundColor(C_BG);
        configureWeb(web);
        column.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(url);
        web.requestFocus();
    }

    private void configureWeb(final WebView view) {
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setTextZoom(textZoom);
        s.setDefaultFontSize(16);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " DSHMobile/1.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return handleUrl(v, request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(v, url);
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (showingError) return;
                if (progress != null) progress.setVisibility(View.VISIBLE);
                setStatus("● 加载中", C_MUTED);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (showingError) return;
                if (progress != null) progress.setVisibility(View.GONE);
                setStatus("● 已连接", C_GREEN);
                CookieManager.getInstance().flush();
                injectMobileCss(v);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    setStatus("● 连接失败", C_RED);
                    showError("无法连接到电脑桥接：" + error.getDescription()
                            + "\n\n请确认手机和电脑在同一局域网，且电脑上的 start-mobile.bat 还在运行。");
                }
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request,
                                            android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()
                        && (response.getStatusCode() == 401 || response.getStatusCode() == 403)) {
                    setStatus("● 配对失效", C_RED);
                    showError("配对链接已失效（HTTP " + response.getStatusCode()
                            + "）。\n\n在电脑上重新运行 start-mobile.bat，然后扫描新的二维码。");
                }
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.cancel();
                showError("HTTPS 证书校验失败。请检查电脑端的 TLS 配置。");
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                if (progress != null) {
                    progress.setProgress(newProgress);
                    progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String host = request.getOrigin() == null ? "" : request.getOrigin().getHost();
                Uri bridge = currentUrl == null ? null : Uri.parse(currentUrl);
                if (bridge != null && host != null && host.equals(bridge.getHost())) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            }
        });

        view.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                beginDownload(url, userAgent, contentDisposition, mimeType, contentLength);
            }
        });
    }

    private void beginDownload(String url, String userAgent, String contentDisposition,
                               String mimeType, long contentLength) {
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadDisposition = contentDisposition;
            pendingDownloadMime = mimeType;
            pendingDownloadLength = contentLength;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
            return;
        }
        enqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition,
                                  String mimeType) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(Uri.parse(url));
            req.addRequestHeader("Cookie", cookie == null ? "" : cookie);
            req.addRequestHeader("User-Agent", userAgent);
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType));
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(MainActivity.this, "已开始下载", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "下载失败：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean handleUrl(WebView v, String url) {
        if (url == null) return false;
        if (url.startsWith("dshmobile://retry")) {
            showingError = false;
            if (web != null && currentUrl != null) web.loadUrl(currentUrl);
            return true;
        }
        if (url.startsWith("dshmobile://setup")) {
            showSetup(currentUrl);
            return true;
        }
        if (url.startsWith("dshmobile://pair")) {
            try {
                String pair = normalize(Uri.parse(url).getQueryParameter("u"));
                if (pair != null) {
                    prefs.edit().putString(KEY_URL, pair).apply();
                    showWeb(pair);
                }
            } catch (Exception ignored) {}
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (isBridgeUrl(url)) return false;
            openExternal(Uri.parse(url));
            return true;
        }
        if (url.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:")) {
            openExternal(Uri.parse(url));
            return true;
        }
        return false;
    }

    private boolean isBridgeUrl(String url) {
        try {
            Uri a = Uri.parse(currentUrl);
            Uri b = Uri.parse(url);
            return a.getHost() != null && a.getHost().equals(b.getHost())
                    && a.getPort() == b.getPort();
        } catch (Exception e) {
            return false;
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void injectMobileCss(WebView v) {
        String js = "(function(){try{"
                + "var m=document.querySelector('meta[name=viewport]');"
                + "if(!m){m=document.createElement('meta');m.name='viewport';"
                + "m.content='width=device-width,initial-scale=1,maximum-scale=5,viewport-fit=cover';"
                + "document.head.appendChild(m);}"
                + "var id='dsh-mobile-style';"
                + "if(document.getElementById(id))return;"
                + "var s=document.createElement('style');s.id=id;"
                + "s.textContent='html{-webkit-text-size-adjust:100%!important}"
                + "body{overscroll-behavior-y:contain}"
                + "textarea,input{font-size:16px!important}';"
                + "document.head.appendChild(s);"
                + "}catch(e){}})();";
        v.evaluateJavascript(js, null);
    }

    private void setStatus(String text, int color) {
        if (statusView != null) {
            statusView.setText(text);
            statusView.setTextColor(color);
        }
    }

    private void showError(String message) {
        if (web == null) return;
        showingError = true;
        String html = "<!doctype html><html><head><meta name='viewport' "
                + "content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:#0B0F14;color:#E5E7EB;"
                + "font-family:sans-serif;padding:28px;line-height:1.6'>"
                + "<div style='font-size:20px;font-weight:700;margin-bottom:14px'>连接失败</div>"
                + "<div style='font-size:14px;color:#94A3B8;white-space:pre-wrap'>"
                + escapeHtml(message) + "</div>"
                + "<div style='margin-top:28px'>"
                + "<a href='dshmobile://retry' style='display:inline-block;padding:12px 22px;"
                + "background:#22D3EE;color:#07111F;border-radius:10px;text-decoration:none;"
                + "font-weight:700;margin-right:12px'>重试</a>"
                + "<a href='dshmobile://setup' style='display:inline-block;padding:12px 22px;"
                + "border:1px solid #334155;color:#E5E7EB;border-radius:10px;"
                + "text-decoration:none'>重新配对</a>"
                + "</div></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /* ---------------------------------------------------------------- menu */

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "刷新");
        popup.getMenu().add(0, 2, 1, "字体大小（当前 " + textZoom + "%）");
        popup.getMenu().add(0, 3, 2, isLandscape() ? "切换为竖屏" : "切换为横屏");
        popup.getMenu().add(0, 4, 3, "在浏览器中打开");
        popup.getMenu().add(0, 5, 4, "重新配对");
        popup.getMenu().add(0, 6, 5, "关于");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        if (web != null) web.reload();
                        return true;
                    case 2:
                        textZoom = textZoom >= 130 ? 85 : textZoom + 15;
                        if (web != null) web.getSettings().setTextZoom(textZoom);
                        Toast.makeText(MainActivity.this, "字体 " + textZoom + "%",
                                Toast.LENGTH_SHORT).show();
                        return true;
                    case 3:
                        setRequestedOrientation(isLandscape()
                                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                        return true;
                    case 4:
                        if (currentUrl != null) openExternal(Uri.parse(currentUrl));
                        return true;
                    case 5:
                        confirmReset();
                        return true;
                    case 6:
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("DSH 手机控制 1.0")
                                .setMessage("电脑端执行，手机端只负责控制和查看。\n\n"
                                        + "桥接把本机 DSH 的网页界面安全地转发到局域网；"
                                        + "配对链接等同于密码，请勿分享。\n\n"
                                        + "只建议在家庭/办公室局域网或 Tailscale 内使用。")
                                .setPositiveButton("知道了", null)
                                .show();
                        return true;
                }
                return false;
            }
        });
        popup.show();
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重新配对？")
                .setMessage("会清除本机保存的配对链接和登录状态。电脑端的桥接不需要重启。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重新配对", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().remove(KEY_URL).apply();
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        showSetup(currentUrl);
                    }
                })
                .show();
    }

    /* -------------------------------------------------------------- results */

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && pendingDownloadUrl != null) {
            enqueueDownload(pendingDownloadUrl, pendingDownloadUserAgent,
                    pendingDownloadDisposition, pendingDownloadMime);
        } else if (pendingDownloadUrl != null) {
            Toast.makeText(this, "没有存储权限，无法保存到“下载”目录", Toast.LENGTH_LONG).show();
        }
        pendingDownloadUrl = null;
        pendingDownloadUserAgent = null;
        pendingDownloadDisposition = null;
        pendingDownloadMime = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            return;
        }
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String raw = data.getStringExtra("result");
            String url = normalize(raw);
            if (url != null) {
                prefs.edit().putString(KEY_URL, url).apply();
                showWeb(url);
            } else {
                Toast.makeText(this, "二维码内容不是 DSH 配对链接", Toast.LENGTH_LONG).show();
            }
        }
    }

    /* -------------------------------------------------------------- lifecycle */

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (showingError) {
            showSetup(currentUrl);
            return;
        }
        if (web != null) {
            if (web.canGoBack()) {
                web.goBack();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                super.onBackPressed();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }

    private void destroyWeb() {
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.loadUrl("about:blank");
            web.clearHistory();
            web.removeAllViews();
            web.destroy();
            web = null;
        }
        fileCallback = null;
        showingError = false;
    }

    /* ---------------------------------------------------------------- helpers */

    private String extractPairUrl(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        if (data == null) return null;
        if ("dshmobile".equals(data.getScheme())) {
            if (!"pair".equals(data.getHost())) return null;
            return normalize(data.getQueryParameter("u"));
        }
        return normalize(data.toString());
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("dshmobile://")) {
            try {
                Uri u = Uri.parse(s);
                String nested = u.getQueryParameter("u");
                if (nested != null) s = nested.trim();
            } catch (Exception e) {
                return null;
            }
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                Uri u = Uri.parse(s);
                if (u.getHost() == null || u.getHost().isEmpty()) return null;
                if (u.getPath() == null || !u.getPath().contains("/pair/")) return null;
                return s;
            } catch (Exception e) {
                return null;
            }
        }
        if (s.matches("^[0-9A-Za-z._-]+(:\\d+)?/pair/.*$")) {
            return "http://" + s;
        }
        return null;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView pill(String label, int fill, int textColor, boolean outline) {
        TextView v = text(label, 15, textColor, true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (fill != 0) bg.setColor(fill);
        if (outline) {
            bg.setColor(0x00000000);
            bg.setStroke(dp(1), 0xFF334155);
        }
        v.setBackground(bg);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}

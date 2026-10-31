.package top.qz0570.jxc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 进销存 App 外壳
 * WebView 直接加载线上地址 https://jxc.0570qz.top
 * JS桥：window.JxcPrinter
 * 新增：悬浮一键删除按钮，调用网页 jxcClearBill() 清空单据明细
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "JxcPrinter";
    private static final String HOME_URL = "https://jxc.0570qz.top";
    private static final int REQ_BT = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final int REQ_FILE = 1003;

    private WebView web;
    private BluetoothPrinter printer;
    private ValueCallback<Uri[]> filePathCallback;
    private Button btnClear; //一键删除悬浮按钮

    public class PrinterBridge {

        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public String platform() {
            return "android-" + Build.VERSION.SDK_INT;
        }

        @JavascriptInterface
        public String listPaired() {
            return printer.listPaired();
        }

        @JavascriptInterface
        public String selectPrinter(String mac, String name) {
            try {
                printer.save(mac, name == null ? "" : name);
                return "";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        @JavascriptInterface
        public String savedPrinter() {
            String mac = printer.savedMac();
            if (mac == null || mac.isEmpty()) return "";
            return mac + "|" + printer.savedName();
        }

        @JavascriptInterface
        public String isBluetoothOn() {
            return printer.isBluetoothOn() ? "1" : "0";
        }

        @JavascriptInterface
        public String hasPermission() {
            return BluetoothPrinter.hasPermission(MainActivity.this) ? "1" : "0";
        }

        @JavascriptInterface
        public String requestPermission() {
            runOnUiThread(() -> BluetoothPrinter.requestPermission(MainActivity.this, REQ_BT));
            return "";
        }

        @JavascriptInterface
        public void print(String text, String encoding) {
            final String t = text == null ? "" : text;
            final String enc = encoding == null ? "GB18030" : encoding;
            new Thread(() -> {
                String r = printer.print(t, enc);
                runOnUiThread(() -> {
                    web.evaluateJavascript("if(window.JxcPrintCallback){window.JxcPrintCallback(" + wrapJsString(r) + ")}", null);
                });
            }).start();
        }

        @JavascriptInterface
        public void testConnect() {
            new Thread(() -> {
                String r = printer.testConnect();
                runOnUiThread(() -> {
                    web.evaluateJavascript("if(window.JxcTestCallback){window.JxcTestCallback(" + wrapJsString(r) + ")}", null);
                });
            }).start();
        }

        @JavascriptInterface
        public String saveImage(String dataUrl, String fileName) {
            try {
                byte[] png = decodeDataUrl(dataUrl);
                if (png == null || png.length == 0) return "图片数据是空的";
                Uri uri = insertToGallery(png, safeFileName(fileName));
                if (uri == null) return "写进相册失败（存储空间或权限问题）";
                return "";
            } catch (OutOfMemoryError e) {
                return "图片太大，内存不够";
            } catch (Exception e) {
                Log.w(TAG, "saveImage 失败", e);
                return "保存失败：" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String shareImage(String dataUrl, String fileName) {
            try {
                byte[] png = decodeDataUrl(dataUrl);
                if (png == null || png.length == 0) return "图片数据是空的";
                String name = safeFileName(fileName);
                final Uri uri = insertToGallery(png, name);
                if (uri == null) return "准备图片失败（存储空间或权限问题）";
                runOnUiThread(() -> {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("image/png");
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Intent chooser = Intent.createChooser(send, "分享凭证图片");
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(chooser);
                    } catch (Exception e) {
                        Log.w(TAG, "打开分享面板失败", e);
                        Toast.makeText(MainActivity.this, "打开分享面板失败", Toast.LENGTH_SHORT).show();
                    }
                });
                return "";
            } catch (OutOfMemoryError e) {
                return "图片太大，内存不够";
            } catch (Exception e) {
                Log.w(TAG, "shareImage 失败", e);
                return "分享失败：" + e.getMessage();
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg == null ? "" : msg, Toast.LENGTH_SHORT).show());
        }
    }

    private static String wrapJsString(String s) {
        if(s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return null;
        String b64 = dataUrl.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
        b64 = b64.replaceAll("\\s", "");
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
    }

    private static String safeFileName(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        if (n.isEmpty()) n = "凭证.png";
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!n.toLowerCase().endsWith(".png")) n = n + ".png";
        return n;
    }

    @SuppressWarnings("deprecation")
    private Uri insertToGallery(byte[] png, String fileName) throws Exception {
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
        cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        if (modern) {
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/进销存");
            cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return null;
        java.io.OutputStream os = null;
        try {
            os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new java.io.IOException("打不开相册写入流");
            os.write(png);
            os.flush();
        } finally {
            if (os != null) {
                try { os.close(); } catch (Exception ignored) {}
            }
        }
        if (modern) {
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        return uri;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        printer = new BluetoothPrinter(this);
        // 外层FrameLayout，用来放webview+悬浮按钮
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ========== 悬浮一键删除按钮 ==========
        btnClear = new Button(this);
        btnClear.setText("一键删除");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(220, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.rightMargin = 40;
        lp.bottomMargin = 120;
        btnClear.setLayoutParams(lp);
        btnClear.setBackgroundColor(0xffdd3333);
        btnClear.setTextColor(0xffffffff);
        btnClear.setOnClickListener(v -> {
            // 调用网页全局清空函数
            web.evaluateJavascript("if(window.jxcClearBill){window.jxcClearBill();}else{alert('网页未实现jxcClearBill')}", null);
        });
        root.addView(btnClear);

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        s.setUserAgentString(s.getUserAgentString() + " JxcApp/1.0");

        web.addJavascriptInterface(new PrinterBridge(), "JxcPrinter");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith(HOME_URL)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            MainActivity.this, android.Manifest.permission.CAMERA)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[]{android.Manifest.permission.CAMERA},
                                REQ_CAMERA);
                    } else {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "打开文件选择器失败", e);
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });

        web.loadUrl(HOME_URL);

        if (!BluetoothPrinter.hasPermission(this)) {
            BluetoothPrinter.requestPermission(this, REQ_BT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, ok ? "蓝牙权限已允许" : "没有蓝牙权限就打不了单，请到系统设置里允许",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.removeJavascriptInterface("JxcPrinter");
            ((ViewGroup)web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}

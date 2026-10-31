package top.qz0570.jxc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 进销存 App 外壳
 *
 * WebView 直接加载 https://jxc.0570qz.top，网页改了手机不用重装 APK。
 *
 * 给网页暴露 window.JxcPrinter：
 *   isAvailable / platform / listPaired / selectPrinter / savedPrinter
 *   isBluetoothOn / hasPermission / requestPermission
 *   print(text, encoding)      打印，返回 "" 表示成功，否则是错误说明
 *   testConnect()              测试连接，返回同上
 *   saveImage(dataUrl, name)   存进系统相册
 *   shareImage(dataUrl, name)  分享给微信等
 *   toast(msg)                 原生提示
 *
 * 2026-10-30 修复：
 *   ① 补上 onShowFileChooser —— 否则网页 <input type="file"> 点了完全没反应
 *   ② setAllowContentAccess 改成 true —— 否则选完文件 WebView 读不到内容
 *   ③ 补上 onActivityResult 接收选择结果
 *   ④ 补上旧版 openFileChooser 兼容（Android 4.4~5.0）
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "JxcPrinter";
    private static final String HOME_URL = "https://jxc.0570qz.top";
    private static final int REQ_BT = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final int REQ_FILE = 1003;
    /** 旧版 WebView（Android 4.4~5.0）走 openFileChooser，单独一个请求码 */
    private static final int REQ_FILE_LEGACY = 1004;

    private WebView web;
    private BluetoothPrinter printer;

    /** 网页 file input 的回调句柄（新 API，返回 Uri[]） */
    private ValueCallback<Uri[]> filePathCallback;
    /** 旧版 WebView 的回调句柄（只返回单个 Uri） */
    @SuppressWarnings("rawtypes")
    private ValueCallback legacyCallback;

    // ===================== JavaScript 接口 =====================
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
        /**
         * 打印。JS 线程调进来的，必须另起线程做 socket 操作，
         * 否则抛 NetworkOnMainThreadException 直接崩。
         * 主线程最多等 20 秒（连打印机慢的时候要等）。
         */
        @JavascriptInterface
        public String print(String text, String encoding) {
            final String t = text == null ? "" : text;
            final String enc = encoding == null ? "GB18030" : encoding;
            final Object lock = new Object();
            final String[] result = {""};
            Thread th = new Thread(() -> {
                String r = printer.print(t, enc);
                synchronized (lock) {
                    result[0] = r;
                    lock.notifyAll();
                }
            });
            th.start();
            synchronized (lock) {
                try {
                    lock.wait(20000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            return result[0];
        }

        @JavascriptInterface
        public String testConnect() {
            final String[] result = {""};
            final Object lock = new Object();
            Thread th = new Thread(() -> {
                String r = printer.testConnect();
                synchronized (lock) {
                    result[0] = r;
                    lock.notifyAll();
                }
            });
            th.start();
            synchronized (lock) {
                try {
                    lock.wait(20000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            return result[0];
        }

        /**
         * 把 dataURL 存进系统相册。
         * WebView 不实现网页下载（<a download> 无效），必须走 MediaStore。
         */
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

        /** 把图片分享给别的应用（微信、QQ 等） */
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

    // ===================== 图片工具 =====================

    /** 解析 dataURL / 纯 base64 → PNG 字节 */
    private static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return null;
        String b64 = dataUrl.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
        b64 = b64.replaceAll("\\s", "");
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
    }

    /** 文件名兜底：去掉非法字符，没有扩展名就补 .png */
    private static String safeFileName(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        if (n.isEmpty()) n = "凭证.png";
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!n.toLowerCase().endsWith(".png")) n = n + ".png";
        return n;
    }

    /**
     * 写进系统相册（MediaStore），返回 content:// URI
     * 安卓 10+ 不需要存储权限；9 及以下要 WRITE_EXTERNAL_STORAGE（Manifest 已声明）
     */
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

    // ===================== 生命周期 =====================

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        printer = new BluetoothPrinter(this);

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 登录状态在 localStorage，必须开
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);
        // ★ 必须 true：文件选择器返回 content:// URI，false 的话 WebView 读不到内容
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
                // 站内链接留在 App，别的跳系统浏览器
                if (url != null && url.startsWith(HOME_URL)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // 扫码/拍照要用摄像头
                runOnUiThread(() -> {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            MainActivity.this, android.Manifest.permission.CAMERA)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[]{android.Manifest.permission.CAMERA},
                                REQ_CAMERA);
                    }
                    request.grant(request.getResources());
                });
            }

            /*
             * ★ 2026-10-31 兼容：Android 4.4~5.0 的 WebView 不调用 onShowFileChooser，
             *   而是反射调用下面这三个 openFileChooser 重载。
             *   没实现的话，老安卓上点「选文件」也是完全没反应。
             *   三个重载都要写（不同版本调用的参数个数不一样）。
             */
            @SuppressWarnings("unused")
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                openFileChooser(uploadMsg, null);
            }

            @SuppressWarnings("unused")
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                openFileChooser(uploadMsg, acceptType, null);
            }

            @SuppressWarnings("unused")
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                legacyCallback = uploadMsg;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_LEGACY);
                } catch (Exception e) {
                    Log.w(TAG, "旧版文件选择器打开失败", e);
                    if (legacyCallback != null) {
                        legacyCallback.onReceiveValue(null);
                        legacyCallback = null;
                    }
                    Toast.makeText(MainActivity.this, "打不开文件选择器（旧版）", Toast.LENGTH_SHORT).show();
                }            }

            /**
             * ★ 网页 <input type="file"> 的入口。
             * 没有这个方法时，点网页的「选文件」完全没反应（被 WebView 静默吞掉）。
             * 拿到结果后必须调 onReceiveValue（哪怕传 null），否则网页会一直卡着。
             */
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;
                // ★ 诊断用：如果点了「选文件」看到这个提示，说明新 API 被调用了
                Toast.makeText(MainActivity.this, "正在打开文件选择器…", Toast.LENGTH_SHORT).show();
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

    /**
     * ★ 接收文件选择器的结果，回传给网页。
     * 三种情况都要处理（多选 / 单选 / 取消），漏一个网页就卡死。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 旧版 WebView 的选择结果（单个 Uri）
        if (requestCode == REQ_FILE_LEGACY) {
            if (legacyCallback != null) {
                Uri result = (resultCode == Activity.RESULT_OK && data != null) ? data.getData() : null;
                legacyCallback.onReceiveValue(result);
                legacyCallback = null;
            }
            return;
        }

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
}

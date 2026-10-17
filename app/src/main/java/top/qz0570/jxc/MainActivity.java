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
 * 【设计要点：不打包网页，直接加载线上地址】
 *   WebView 直接打开 https://jxc.0570qz.top，好处是：
 *     - 以后网页改了、服务器升级了，**手机不用重装 APK**，打开就是最新版
 *     - APK 里没有业务代码，泄不泄露都无所谓，也不用跟着每次发版
 *   代价是必须联网（本来就是个联网系统，无所谓）。
 *
 * 【给网页暴露的能力】
 *   window.JxcPrinter.xxx() —— 网页里判断到这个对象存在，就显示"蓝牙打印"按钮：
 *     isAvailable()            是否可用（网页用它决定要不要显示按钮）
 *     listPaired()             已配对设备（JSON）
 *     selectPrinter(mac, name) 选定并记住打印机
 *     savedPrinter()           当前选的是哪台
 *     print(text, encoding)    打印（针式机用 GB18030）
 *     testConnect()            测试连接
 *     saveImage(dataUrl, name) 把图片存进系统相册（WebView 不支持网页下载，必须走原生）
 *     shareImage(dataUrl, name) 把图片分享给微信等应用（WebView 没有 Web Share API）
 *     toast(msg)               让原生弹个提示
 *   所有 @JavascriptInterface 方法都返回字符串（空串=成功，否则是错误说明），
 *   这样网页端不用做异常处理，也不会因为原生抛异常导致网页崩掉。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "JxcPrinter";
    private static final String HOME_URL = "https://jxc.0570qz.top";
    private static final int REQ_BT = 1001;
    private static final int REQ_CAMERA = 1002;

    private WebView web;
    private BluetoothPrinter printer;

    // JavaScript 接口对象：网页通过 window.JxcPrinter 调用
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
         * 打印。注意：这里是 JS 线程调进来的，必须另起线程做 socket 操作，
         * 否则安卓会抛 NetworkOnMainThreadException 直接崩。
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
            // 等最多 20 秒（连打印机慢的时候要等）
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
         * 把一张图片（dataURL）**存进系统相册**
         *
         * 【为什么必须走原生】
         *   App 是 WebView 壳子，而 WebView **不实现网页下载**：
         *   网页里 `<a download href="data:image/png;base64,...">` 点击后什么都不会发生。
         *   以前网页就调那个，还提示"已保存" —— 用户去相册根本找不到（真踩过）。
         *   正确做法是用 MediaStore 把字节写进相册目录，系统媒体库会自动索引。
         *
         * @param dataUrl  "data:image/png;base64,xxxx" 或纯 base64
         * @param fileName 文件名，如 销售出库凭证-PFXS20261016005.png
         * @return 空串=成功；否则是给用户看的错误说明
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

        /**
         * 把一张图片**分享给别的应用**（微信、QQ 等）
         *
         * 做法：先写进 MediaStore 拿到 content:// URI（这样别的应用有读权限，
         * 不用配 FileProvider、不用改 Manifest），再把 URI 丢给系统分享面板。
         *
         * @return 空串=已把分享面板打开；否则是错误说明
         */
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
                        // 授予临时读权限，微信之类的应用才能读到这张图
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

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg == null ? "" : msg, Toast.LENGTH_SHORT).show());
        }
    }

    // =======================================================================
    // 图片保存 / 分享的底层实现（供 PrinterBridge 调用）
    // =======================================================================

    /**
     * 解析 dataURL / 纯 base64 → PNG 字节
     * 支持 "data:image/png;base64,xxxx" 和裸 base64 两种写法
     */
    private static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return null;
        String b64 = dataUrl.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
        // 去掉换行等空白（有些环境会插入）
        b64 = b64.replaceAll("\\s", "");
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
    }

    /** 文件名兜底：去掉路径分隔符和非法字符，没有就给个默认名 */
    private static String safeFileName(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        if (n.isEmpty()) n = "凭证.png";
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!n.toLowerCase().endsWith(".png")) n = n + ".png";
        return n;
    }

    /**
     * 把 PNG 字节写进系统相册（MediaStore），返回 content:// URI
     *
     * 【安卓版本差异】
     *   10 (API 29) 及以上：用 RELATIVE_PATH 指定相册子目录，**不需要任何存储权限**
     *   9 及以下：要 WRITE_EXTERNAL_STORAGE 权限（Manifest 里已声明，仅 ≤28 生效）
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
        s.setDomStorageEnabled(true);          // 登录状态存在 localStorage 里，必须开
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);           // 不需要本地文件，关掉更安全
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        // 让网页里能判断"我在 App 里"，也能拿到 UA
        s.setUserAgentString(s.getUserAgentString() + " JxcApp/1.0");

        web.addJavascriptInterface(new PrinterBridge(), "JxcPrinter");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 站内链接留在 App 里，别的跳系统浏览器
                if (url != null && url.startsWith(HOME_URL)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                    // 打不开就算了
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // 扫码要用摄像头：网页申请的权限在这里放行（安卓权限本身还要用户同意）
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

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });

        web.loadUrl(HOME_URL);

        // 一进来就申请蓝牙权限（安卓 12+ 需要）
        if (!BluetoothPrinter.hasPermission(this)) {
            BluetoothPrinter.requestPermission(this, REQ_BT);
        }
    }

    @Override
    public void onBackPressed() {
        // 网页里能返回就返回，否则退出 App
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

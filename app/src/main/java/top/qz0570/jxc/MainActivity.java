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

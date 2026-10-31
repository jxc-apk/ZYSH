package top.qz0570.jxc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * 本版修复：
 *   ① onShowFileChooser 补「相机」入口 —— 原来只有系统文件选择器，里面没有拍照项
 *   ② 补 FileProvider + 拍照临时文件 —— 否则 ACTION_IMAGE_CAPTURE 写不进去
 *   ③ onActivityResult 补「相机返回 data 为空」的分支 —— 拍完了图丢了就是这里
 *   ④ onPermissionRequest 的 grant 挪到授权回调里 —— 原来请求前就 grant，等于没授权
 *   ⑤ print/testConnect 线程异常兜底 —— 原来异常时线程静默死掉，主线程等满超时返回 ""，
 *      网页会把 "" 当成「打印成功」，用户以为打出来了，实际没打
 *   ⑥ 打印串行化 + 超时返回明确错误文案
 *   ⑦ 安卓 10 及以下存图补 WRITE_EXTERNAL_STORAGE 运行时权限
 *   ⑧ 补 onPause/onResume/onDestroy，断网兜底页，域名精确校验
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "JxcPrinter";
    private static final String HOME_URL = "https://jxc.0570qz.top";
    private static final String HOME_HOST = "jxc.0570qz.top";

    private static final int REQ_BT = 1001;          // 蓝牙
    private static final int REQ_CAMERA = 1002;      // getUserMedia 摄像头
    private static final int REQ_FILE = 1003;        // 文件/拍照结果
    private static final int REQ_CHOOSER_CAM = 1004; // 弹选择器之前的相机权限
    private static final int REQ_STORAGE = 1005;     // 安卓 10 及以下存图

    /** 同步打印最多等多久。JS 桥方法跑在 JavaBridge 线程上，等太久网页会明显卡住 */
    private static final long PRINT_TIMEOUT_MS = 12000;

    private WebView web;
    private BluetoothPrinter printer;

    /** 网页 file input 的回调句柄，拿到结果后必须回调 onReceiveValue */
    private ValueCallback<Uri[]> filePathCallback;
    private WebChromeClient.FileChooserParams pendingFileParams;
    private Uri cameraOutputUri;
    private File cameraOutputFile;

    /** getUserMedia 的权限请求，必须存下来，授权回调里再 grant */
    private PermissionRequest pendingPermReq;

    /** 打印串行队列：两次点击不会并发写同一个蓝牙 socket，避免小票内容交错 */
    private final ExecutorService printExec = Executors.newSingleThreadExecutor();

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
         * 打印。JS 线程同步调用，网页在等这个返回值。
         *
         * 三个必须守住的约定：
         *   1. 成功返回 ""，失败返回非空错误说明 —— 绝不能异常后还返回 ""
         *   2. 走单线程队列，不并发
         *   3. 超时返回明确文案，不能返回 ""
         */
        @JavascriptInterface
        public String print(String text, String encoding) {
            final String t = text == null ? "" : text;
            final String enc = encoding == null ? "GB18030" : encoding;
            return runPrintTask(() -> printer.print(t, enc), PRINT_TIMEOUT_MS, "打印机无响应");
        }

        @JavascriptInterface
        public String testConnect() {
            return runPrintTask(() -> printer.testConnect(), PRINT_TIMEOUT_MS, "连接打印机超时");
        }

        /**
         * 异步打印（新增，网页可择机迁移）。
         * 同步版会把 JS 线程堵住，打印机离线时网页会卡十几秒；
         * 改成这个版本，网页先显示 loading，好了走 JxcPrintCallback 回来。
         */
        @JavascriptInterface
        public void printAsync(String text, String encoding) {
            final String t = text == null ? "" : text;
            final String enc = encoding == null ? "GB18030" : encoding;
            printExec.execute(() -> {
                String r;
                try {
                    r = printer.print(t, enc);
                } catch (Throwable e) {
                    Log.w(TAG, "打印异常", e);
                    r = "打印异常：" + e.getMessage();
                }
                postPrintCallback("JxcPrintCallback", r);
            });
        }

        /**
         * 把 dataURL 存进系统相册。
         * WebView 不实现网页下载（<a download> 无效），必须走 MediaStore。
         */
        @JavascriptInterface
        public String saveImage(String dataUrl, String fileName) {
            if (!ensureStoragePermission()) {
                return "请先允许「存储」权限，然后再点一次保存";
            }
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
            if (!ensureStoragePermission()) {
                return "请先允许「存储」权限，然后再点一次分享";
            }
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
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    msg == null ? "" : msg, Toast.LENGTH_SHORT).show());
        }
    }

    // ===================== 打印任务包装 =====================

    /**
     * 同步等任务结果。关键点：任务里抛任何异常都必须把 done 置位并写错误文案，
     * 否则这里会白等到超时，然后返回一个空串被网页当成成功。
     */
    private String runPrintTask(Callable<String> task, long timeoutMs, String timeoutMsg) {
        final Object lock = new Object();
        final String[] out = new String[1];
        final boolean[] done = new boolean[1];

        printExec.execute(() -> {
            String r;
            try {
                r = task.call();
            } catch (Throwable e) {
                Log.w(TAG, "蓝牙任务异常", e);
                r = "打印异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            if (r == null) r = "";
            synchronized (lock) {
                out[0] = r;
                done[0] = true;
                lock.notifyAll();
            }
        });

        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!done[0]) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!done[0]) {
                return timeoutMsg + "（已等待 " + (timeoutMs / 1000) + " 秒），请检查打印机是否开机、是否在范围内";
            }
            return out[0];
        }
    }

    private void postPrintCallback(String fn, String result) {
        runOnUiThread(() -> {
            if (web == null) return; // 页面已销毁，别再回调
            web.evaluateJavascript("if(window." + fn + "){window." + fn + "("
                    + wrapJsString(result) + ")}", null);
        });
    }

    private static String wrapJsString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    // ===================== 图片工具 =====================

    /** 解析 dataURL / 纯 base64 → PNG 字节 */
    private static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return null;
        String b64 = dataUrl.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
        b64 = b64.replaceAll("\\s", "");
        return Base64.decode(b64, Base64.DEFAULT);
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
     * 安卓 10+ 不需要存储权限；9 及以下要 WRITE_EXTERNAL_STORAGE
     */
    private Uri insertToGallery(byte[] png, String fileName) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        if (modern) {
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/进销存");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return null;
        OutputStream os = null;
        try {
            os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("打不开相册写入流");
            os.write(png);
            os.flush();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (modern) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        return uri;
    }

    private boolean ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        runOnUiThread(() -> ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
        return false;
    }

    // ===================== 生命周期 =====================

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

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
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                    // 防系统字体放大撑破网页布局
        s.setAllowFileAccess(false);
        // ★ 必须 true：文件选择器返回 content:// URI，false 的话 WebView 读不到内容
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        s.setUserAgentString(s.getUserAgentString() + " JxcApp/1.0");

        web.addJavascriptInterface(new PrinterBridge(), "JxcPrinter");

        web.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    return handleUrl(request.getUrl().toString());
                }
                return super.shouldOverrideUrlLoading(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // target=_blank 在 WebView 里点了没反应，统一改成当前页跳转
                view.evaluateJavascript(
                        "(function(){if(window.__jxcTbFix)return;window.__jxcTbFix=1;"
                                + "document.addEventListener('click',function(e){"
                                + "var t=e.target;if(!t||!t.closest)return;"
                                + "var a=t.closest('a[target=\"_blank\"]');if(a){a.target='_self';}},true);})();",
                        null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    showOfflinePage();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    showOfflinePage();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {

            /**
             * 扫码/拍照要用摄像头。
             * ★ 关键：没有权限时必须先把 request 存起来，等用户点了「允许」再 grant。
             *   原来写法是 requestPermissions 之后立刻 grant，那一刻权限还没下来，
             *   grant 会被忽略，网页摄像头永远黑屏。
             */
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean granted = ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    if (granted) {
                        request.grant(request.getResources());
                    } else {
                        pendingPermReq = request;
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            /**
             * 网页 <input type="file"> 的入口。
             * ★ 系统 params.createIntent() 给的是 ACTION_GET_CONTENT，里面没有「相机」，
             *   所以网页上点拍照等于没有入口 —— 这是拍不了照的根本原因。
             *   这里自己组 chooser，把相机通过 EXTRA_INITIAL_INTENTS 塞进去。
             */
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                pendingFileParams = params;
                cameraOutputUri = null;
                cameraOutputFile = null;

                // 声明了 CAMERA 但未授权时，启动相机会直接 SecurityException，先要权限
                if (acceptWantsImage(params.getAcceptTypes())
                        && cameraPermissionDeclared()
                        && ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, REQ_CHOOSER_CAM);
                    return true; // 已接管，必须 true
                }

                openChooser(params);
                return true;
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
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        printExec.shutdownNow();
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (pendingPermReq != null) {
            pendingPermReq.deny();
            pendingPermReq = null;
        }
        if (web != null) {
            try {
                web.stopLoading();
                web.setWebChromeClient(null);
                web.setWebViewClient(null);
                web.removeJavascriptInterface("JxcPrinter");
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) parent.removeView(web);
                web.destroy();
            } catch (Exception e) {
                Log.w(TAG, "销毁 WebView 异常", e);
            }
            web = null;
        }
        super.onDestroy();
    }

    // ===================== 文件选择 =====================

    private void openChooser(WebChromeClient.FileChooserParams params) {
        Intent contentIntent = null;
        try {
            contentIntent = params.createIntent();
        } catch (Exception e) {
            Log.w(TAG, "createIntent 失败", e);
        }
        if (contentIntent == null) {
            contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
            contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
            contentIntent.setType("*/*");
        }
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        Intent captureIntent = acceptWantsImage(params.getAcceptTypes()) ? buildCaptureIntent() : null;

        // 网页写了 capture 属性：直接进相机
        if (captureIntent != null && params.isCaptureEnabled()) {
            if (launch(captureIntent, REQ_FILE)) return;
            captureIntent = null;
            cameraOutputUri = null;
            cameraOutputFile = null;
        }

        Intent chooser;
        if (captureIntent != null) {
            chooser = Intent.createChooser(contentIntent, "选择图片");
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{captureIntent});
        } else {
            chooser = contentIntent;
        }

        if (!launch(chooser, REQ_FILE)) {
            failFileChooser("手机上没有可用的文件选择器或相机");
        }
    }

    private boolean launch(Intent intent, int code) {
        try {
            startActivityForResult(intent, code);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "启动 Intent 失败", e);
            return false;
        }
    }

    private Intent buildCaptureIntent() {
        // 声明了 CAMERA 但没授权时，启动 ACTION_IMAGE_CAPTURE 会直接 SecurityException
        if (cameraPermissionDeclared()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            File dir = new File(getCacheDir(), "capture");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, "cap_" + System.currentTimeMillis() + ".jpg");
            // 必须 content://：setAllowFileAccess(false) 下 file:// 会被相机拒写
            Uri out = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cameraOutputUri = out;
            cameraOutputFile = f;
            return i;
        } catch (Exception e) {
            // 99% 是 AndroidManifest 里没配 provider，或 authorities 和这里不一致
            Log.w(TAG, "构造拍照 Intent 失败（检查 FileProvider 配置）", e);
            cameraOutputUri = null;
            cameraOutputFile = null;
            return null;
        }
    }

    private void failFileChooser(String msg) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        pendingFileParams = null;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static boolean acceptWantsImage(String[] accepts) {
        if (accepts == null || accepts.length == 0) return true;
        for (String a : accepts) {
            if (a == null) continue;
            String v = a.toLowerCase();
            if (v.startsWith("image/") || v.contains("image/*") || v.equals("*/*")) return true;
        }
        return false;
    }

    private boolean cameraPermissionDeclared() {
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] ps = pi.requestedPermissions;
            if (ps != null) {
                for (String p : ps) {
                    if (Manifest.permission.CAMERA.equals(p)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ===================== 结果回调 =====================

    /**
     * 接收文件选择器的结果，回传给网页。
     * ★ 相机这一路 data 是 null，真正的图在 EXTRA_OUTPUT 指向的文件里，
     *   原来只判断 data != null，所以拍完照等于「取消了」，图就丢了。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) return;

        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK) {
            boolean captureOk = cameraOutputUri != null && cameraOutputFile != null
                    && cameraOutputFile.exists() && cameraOutputFile.length() > 0;
            if (captureOk) {
                // 优先用指定输出文件：部分 ROM 从选择器进相机后只回传压缩缩略图
                results = new Uri[]{cameraOutputUri};
            } else if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }

        boolean usedCapture = results != null && results.length > 0
                && cameraOutputUri != null && cameraOutputUri.equals(results[0]);
        if (!usedCapture && cameraOutputFile != null && cameraOutputFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cameraOutputFile.delete(); // 用户改选了文件，清掉空拍图
        }
        cameraOutputUri = null;
        cameraOutputFile = null;

        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
        pendingFileParams = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == REQ_BT) {
            Toast.makeText(this, ok ? "蓝牙权限已允许" : "没有蓝牙权限就打不了单，请到系统设置里允许",
                    Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_CAMERA) {
            // 拿到用户选择后再回传给 WebView，否则摄像头一直黑屏
            if (pendingPermReq != null) {
                if (ok) pendingPermReq.grant(pendingPermReq.getResources());
                else pendingPermReq.deny();
                pendingPermReq = null;
            }
            if (!ok) {
                Toast.makeText(this, "没有相机权限，扫码/拍照不可用", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_CHOOSER_CAM) {
            // 允许与否都继续弹选择框：允许则带相机，拒绝则只剩文件
            if (pendingFileParams != null) {
                if (!ok) Toast.makeText(this, "未授权相机，只能从文件中选择",
                        Toast.LENGTH_SHORT).show();
                openChooser(pendingFileParams);
            } else {
                failFileChooser("选择文件失败，请重试");
            }
        } else if (requestCode == REQ_STORAGE) {
            if (!ok) {
                Toast.makeText(this, "没有存储权限，凭证图片无法保存到相册",
                        Toast.LENGTH_LONG).show();
            }
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

    // ===================== 工具 =====================

    private boolean handleUrl(String url) {
        if (url == null) return false;
        Uri u;
        try {
            u = Uri.parse(url);
        } catch (Exception e) {
            return true;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        String host = u.getHost() == null ? "" : u.getHost();

        // 站内链接留在 App；用 host 精确比对，避免 jxc.0570qz.top.xxx.com 蒙混过关
        if (("http".equals(scheme) || "https".equals(scheme)) && HOME_HOST.equalsIgnoreCase(host)) {
            return false;
        }
        if ("about".equals(scheme) || "blob".equals(scheme)
                || "data".equals(scheme) || "javascript".equals(scheme)) {
            return false; // 交回 WebView 处理，别外抛
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            Toast.makeText(this, "打不开这个链接", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void showOfflinePage() {
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:-apple-system,sans-serif;text-align:center;padding:90px 24px;color:#444}"
                + "p{color:#888;font-size:14px}"
                + "button{margin-top:26px;padding:12px 30px;font-size:16px;border:0;border-radius:8px;"
                + "background:#2f7cf6;color:#fff}</style>"
                + "<h3>网络连接失败</h3><p>请检查手机网络后重试</p>"
                + "<button onclick=\"location.href='" + HOME_URL + "'\">重新加载</button>";
        if (web != null) {
            web.loadDataWithBaseURL(HOME_URL, html, "text/html", "utf-8", null);
        }
    }
}

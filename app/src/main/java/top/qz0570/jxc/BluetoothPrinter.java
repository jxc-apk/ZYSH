package top.qz0570.jxc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.UUID;

/**
 * 蓝牙打印（蓝牙经典 SPP → 针式打印机）
 *
 * 【为什么只能用原生，网页做不到】
 *   浏览器只有 Web Bluetooth，它只能连低功耗蓝牙（BLE GATT）。
 *   针式打印机（得实 DS-1900 这类）的蓝牙是**经典蓝牙串口（SPP）**，
 *   浏览器规范里根本没有连 SPP 的能力。所以必须由原生这一层来发字节。
 *
 * 【为什么连接要试三种方式】
 *   这是蓝牙打印最常见的坑：同一个 UUID，有的打印机只认"安全连接"，
 *   有的只认"非安全连接"，还有的老机器只认固定的 channel 1。
 *   只试一种，用户手机上就会看到"连接失败"却不知道为什么。
 *   这里依次尝试三种，成功了记住用的是哪种，下次直接用。
 *
 * 【中文怎么编码】
 *   针式机是文本模式，直接用打印机自带字库打汉字，所以要把字符串编成 GB18030。
 *   安卓系统自带 GB18030 字符集，不需要引入任何第三方库。
 */
public class BluetoothPrinter {

    private static final String TAG = "JxcPrinter";

    /** 蓝牙串口（SPP）的标准 UUID，几乎所有串口型打印机都用它 */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final String PREF = "jxc_printer";
    private static final String KEY_MAC = "printer_mac";
    private static final String KEY_NAME = "printer_name";
    /** 记住上次成功的连接方式：0=安全 1=非安全 2=channel1 */
    private static final String KEY_MODE = "printer_mode";

    private final Context ctx;

    public BluetoothPrinter(Context ctx) {
        this.ctx = ctx;
    }

    // ---------------- 权限 ----------------

    /** 安卓 12+ 连蓝牙需要运行时授权 */
    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestPermission(Activity act, int reqCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    act,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    },
                    reqCode);
        }
    }

    // ---------------- 设备 ----------------

    private BluetoothAdapter adapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothOn() {
        BluetoothAdapter a = adapter();
        try {
            return a != null && a.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 已配对的设备列表（必须先配对，App 不负责配对） */
    public String listPaired() {
        JSONArray arr = new JSONArray();
        try {
            BluetoothAdapter a = adapter();
            if (a == null) return arr.toString();
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded == null) return arr.toString();
            for (BluetoothDevice d : bonded) {
                JSONObject o = new JSONObject();
                o.put("name", d.getName() == null ? "" : d.getName());
                o.put("mac", d.getAddress());
                arr.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "listPaired 失败: " + e.getMessage());
        }
        return arr.toString();
    }

    public void save(String mac, String name) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_MAC, mac).putString(KEY_NAME, name).apply();
    }

    public String savedMac() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MAC, "");
    }

    public String savedName() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_NAME, "");
    }

    // ---------------- 连接 ----------------

    /** 依次尝试三种连接方式，返回成功的 socket（全失败抛异常，带原因） */
    @SuppressLint("MissingPermission")
    private BluetoothSocket connect(BluetoothDevice device) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int saved = sp.getInt(KEY_MODE, -1);

        StringBuilder err = new StringBuilder();
        // 顺序：上次成功的优先，其次非安全（实测最常成功），最后安全/channel1
        int[] order = saved >= 0
                ? new int[]{saved, 1, 0, 2}
                : new int[]{1, 0, 2};

        for (int mode : order) {
            BluetoothSocket socket = null;
            try {
                if (mode == 0) {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                } else if (mode == 1) {
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                } else {
                    // 老机器兼容：反射调 createRfcommSocket(channel=1)
                    Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                    socket = (BluetoothSocket) m.invoke(device, 1);
                }
                if (socket == null) continue;
                // 连接期间不要同时扫描，否则会拖慢甚至失败
                BluetoothAdapter a = adapter();
                if (a != null && a.isDiscovering()) a.cancelDiscovery();
                socket.connect();
                sp.edit().putInt(KEY_MODE, mode).apply();
                Log.i(TAG, "连接成功，方式=" + mode);
                return socket;
            } catch (Exception e) {
                err.append("方式").append(mode).append(":").append(e.getMessage()).append("; ");
                try {
                    if (socket != null) socket.close();
                } catch (Exception ignored) {
                    // 关不掉就算了，继续试下一种
                }
            }
        }
        throw new Exception("连接打印机失败（三种方式都试过了）" + err);
    }

    /**
     * 打印一段文本
     * @param text     要打印的内容（含 ESC/P 控制码）
     * @param encoding 字符编码，针式机用 GB18030；热敏小票机一般是 GBK
     * @return 空串=成功，否则是错误说明
     */
    public String print(String text, String encoding) {
        if (!hasPermission(ctx)) return "没有蓝牙权限，请在系统设置里允许本应用使用附近的设备";
        BluetoothAdapter a = adapter();
        if (a == null) return "这台手机不支持蓝牙";
        if (!isBluetoothOn()) return "蓝牙没打开，请先在系统里打开蓝牙";

        String mac = savedMac();
        if (mac == null || mac.isEmpty()) return "还没选择打印机，请先选择打印机";

        BluetoothSocket socket = null;
        try {
            BluetoothDevice device = a.getRemoteDevice(mac);
            socket = connect(device);
            OutputStream out = socket.getOutputStream();
            String cs = (encoding == null || encoding.isEmpty()) ? "GB18030" : encoding;
            byte[] data = text.getBytes(Charset.forName(cs));
            out.write(data);
            out.flush();
            // 让打印机把缓冲打完再断，不然末尾会被截掉
            Thread.sleep(400);
            return "";
        } catch (SecurityException e) {
            return "蓝牙权限被拒绝：" + e.getMessage();
        } catch (Exception e) {
            return e.getMessage() == null ? "打印失败" : e.getMessage();
        } finally {
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }

    /** 测试连通性：连上就断开，不真打东西 */
    public String testConnect() {
        if (!hasPermission(ctx)) return "没有蓝牙权限，请在系统设置里允许本应用使用附近的设备";
        BluetoothAdapter a = adapter();
        if (a == null) return "这台手机不支持蓝牙";
        if (!isBluetoothOn()) return "蓝牙没打开，请先在系统里打开蓝牙";
        String mac = savedMac();
        if (mac == null || mac.isEmpty()) return "还没选择打印机";
        BluetoothSocket socket = null;
        try {
            socket = connect(a.getRemoteDevice(mac));
            return "";
        } catch (Exception e) {
            return e.getMessage() == null ? "连接失败" : e.getMessage();
        } finally {
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }
}

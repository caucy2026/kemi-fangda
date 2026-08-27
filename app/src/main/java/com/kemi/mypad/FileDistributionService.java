package com.kemi.mypad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public final class FileDistributionService extends Service {
    public static final String ACTION_START = "com.kemi.mypad.START_DISTRIBUTION";
    public static final String ACTION_STOP = "com.kemi.mypad.STOP_DISTRIBUTION";
    public static final int FIXED_PORT = 8686;
    private static final String CHANNEL = "file_distribution";
    private static final String TAG = "MyPadDistribution";
    private static volatile FileDistributionService instance;

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile int port;

    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    public static List<String> accessUrls() {
        FileDistributionService service = instance;
        if (service == null || !service.running) return new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (String ip : localIpv4Addresses()) urls.add("http://" + ip + ":" + service.port);
        return urls;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "文件分发", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(8686, notification());
        ensureStarted();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopServer();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("KEMI Pads · 文件分发中")
                .setContentText("同一局域网可访问下载目录")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private synchronized void ensureStarted() {
        if (running) return;
        for (int candidate : new int[]{FIXED_PORT, 0}) {
            try {
                ServerSocket socket = new ServerSocket(candidate);
                socket.setReuseAddress(true);
                serverSocket = socket;
                port = socket.getLocalPort();
                running = true;
                new Thread(() -> acceptLoop(socket), "mypad-file-server").start();
                Log.i(TAG, "started on " + port);
                return;
            } catch (Exception error) {
                Log.w(TAG, "port unavailable " + candidate, error);
            }
        }
        stopSelf();
    }

    private void acceptLoop(ServerSocket socket) {
        while (running && !socket.isClosed()) {
            try {
                Socket client = socket.accept();
                new Thread(() -> handleClient(client), "mypad-file-client").start();
            } catch (SocketException closed) {
                break;
            } catch (Exception error) {
                Log.w(TAG, "accept failed", error);
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(8000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                writeText(socket.getOutputStream(), 405, "仅支持 GET", "text/plain; charset=utf-8");
                return;
            }
            String range = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
            }
            String target = parts[1];
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            if ("/health".equals(path)) {
                writeText(socket.getOutputStream(), 200, "ok", "text/plain; charset=utf-8");
            } else if ("/api/files".equals(path)) {
                writeJsonListing(socket.getOutputStream(), queryValue(query, "path"));
            } else if ("/download".equals(path)) {
                writeFile(socket.getOutputStream(), queryValue(query, "path"), range);
            } else if ("/".equals(path)) {
                writeText(socket.getOutputStream(), 200, buildHtml(queryValue(query, "path")), "text/html; charset=utf-8");
            } else {
                writeText(socket.getOutputStream(), 404, "未找到", "text/plain; charset=utf-8");
            }
        } catch (Exception error) {
            Log.w(TAG, "client failed", error);
        }
    }

    private void writeJsonListing(OutputStream output, String relative) throws Exception {
        File directory = safeFile(relative);
        if (directory == null || !directory.isDirectory()) {
            writeText(output, 404, "{\"error\":\"目录不存在\"}", "application/json; charset=utf-8");
            return;
        }
        JSONArray items = new JSONArray();
        File[] files = directory.listFiles();
        if (files != null) {
            List<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, files);
            sorted.sort(Comparator.comparing(File::isFile).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            File root = downloadsRoot();
            for (File file : sorted) {
                JSONObject item = new JSONObject();
                item.put("name", file.getName());
                item.put("path", relativePath(root, file));
                item.put("directory", file.isDirectory());
                item.put("size", file.isFile() ? file.length() : 0);
                item.put("modified", file.lastModified());
                items.put(item);
            }
        }
        JSONObject body = new JSONObject();
        body.put("name", "KEMI Pads");
        body.put("path", relative == null ? "" : relative);
        body.put("items", items);
        writeText(output, 200, body.toString(), "application/json; charset=utf-8");
    }

    private void writeFile(OutputStream output, String relative, String rangeHeader) throws Exception {
        File file = safeFile(relative);
        if (file == null || !file.isFile()) {
            writeText(output, 404, "文件不存在", "text/plain; charset=utf-8");
            return;
        }
        long length = file.length();
        long start = 0;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String first = rangeHeader.substring(6).split("-")[0];
            try { start = Math.max(0, Long.parseLong(first)); } catch (Exception ignored) { start = 0; }
        }
        if (start >= length) start = 0;
        long sendLength = length - start;
        int status = start > 0 ? 206 : 200;
        String encodedName = URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20");
        StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(status == 206 ? " Partial Content" : " OK").append("\r\n")
                .append("Content-Type: application/octet-stream\r\n")
                .append("Content-Length: ").append(sendLength).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Disposition: attachment; filename*=UTF-8''").append(encodedName).append("\r\n");
        if (status == 206) headers.append("Content-Range: bytes ").append(start).append('-').append(length - 1).append('/').append(length).append("\r\n");
        headers.append("Connection: close\r\n\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        try (FileInputStream input = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < start) skipped += input.skip(start - skipped);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        output.flush();
    }

    private String buildHtml(String relative) throws Exception {
        File directory = safeFile(relative);
        if (directory == null || !directory.isDirectory()) relative = "";
        directory = safeFile(relative);
        StringBuilder rows = new StringBuilder();
        if (relative != null && !relative.isEmpty()) {
            String parent = new File(relative).getParent();
            rows.append("<a class='row' href='/?path=").append(enc(parent == null ? "" : parent)).append("'>↩ 返回上一级</a>");
        }
        File[] files = directory == null ? null : directory.listFiles();
        if (files != null) {
            List<File> sorted = new ArrayList<>(); Collections.addAll(sorted, files);
            sorted.sort(Comparator.comparing(File::isFile).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            for (File file : sorted) {
                String rel = relativePath(downloadsRoot(), file);
                rows.append("<a class='row' href='").append(file.isDirectory() ? "/?path=" : "/download?path=")
                        .append(enc(rel)).append("'><b>").append(file.isDirectory() ? "📁 " : "📄 ")
                        .append(escape(file.getName())).append("</b><span>")
                        .append(file.isDirectory() ? "文件夹" : humanBytes(file.length())).append("</span></a>");
            }
        }
        return "<!doctype html><html lang='zh'><meta charset='utf-8'><meta name='viewport' content='width=device-width'>"
                + "<title>KEMI Pads 文件分发</title><style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#f5f7f9;color:#1f2933;margin:0}.box{max-width:860px;margin:36px auto;background:white;border:1px solid #dbe3e8;border-radius:18px;overflow:hidden}.head{padding:24px 28px;border-bottom:1px solid #e5eaee}.head h1{margin:0 0 7px;font-size:25px}.head p{margin:0;color:#687681}.row{display:flex;justify-content:space-between;align-items:center;padding:17px 28px;color:#1f2933;text-decoration:none;border-bottom:1px solid #eef2f4}.row:hover{background:#edf8f6}.row span{color:#687681}.note{padding:16px 28px;color:#687681;background:#fbfcfd}</style>"
                + "<div class='box'><div class='head'><h1>KEMI Pads · 下载</h1><p>同一局域网内，点击文件即可下载</p></div>" + rows
                + "<div class='note'>文件直接来自本机下载目录，不经过互联网。局域网 HTTP 显示“不安全”属于正常提示。</div></div></html>";
    }

    private static void writeText(OutputStream output, int status, String value, String contentType) throws Exception {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Method Not Allowed";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + contentType
                + "\r\nContent-Length: " + body.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private File safeFile(String relative) {
        try {
            File root = downloadsRoot().getCanonicalFile();
            String clean = relative == null ? "" : relative;
            File target = clean.isEmpty() ? root : new File(root, clean).getCanonicalFile();
            if (!target.getPath().equals(root.getPath()) && !target.getPath().startsWith(root.getPath() + File.separator)) return null;
            return target;
        } catch (Exception ignored) { return null; }
    }

    private static File downloadsRoot() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private static String relativePath(File root, File file) throws Exception {
        return root.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString().replace(File.separatorChar, '/');
    }

    private static String queryValue(String query, String key) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq >= 0 && key.equals(part.substring(0, eq))) {
                try { return URLDecoder.decode(part.substring(eq + 1), "UTF-8"); } catch (Exception ignored) { return ""; }
            }
        }
        return "";
    }

    private static String enc(String value) { try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); } catch (Exception ignored) { return ""; } }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d; if (kb < 1024) return String.format(Locale.CHINA, "%.1f KB", kb);
        double mb = kb / 1024d; if (mb < 1024) return String.format(Locale.CHINA, "%.1f MB", mb);
        return String.format(Locale.CHINA, "%.1f GB", mb / 1024d);
    }

    private static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isAnyLocalAddress() && !address.isLinkLocalAddress()) result.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(result);
        return result;
    }

    private synchronized void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        serverSocket = null;
    }
}

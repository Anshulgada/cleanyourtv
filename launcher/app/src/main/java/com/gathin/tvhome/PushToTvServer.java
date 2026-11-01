package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PushToTvServer {
    private static final String TAG = "PushToTvServer";
    public static final int PORT = 8080;

    public interface PushToTvListener {
        void onOpenUrl(String url, String mode, String title);
        void onDispatchKey(int keyCode);
        void onTypeText(String text);
        boolean onHibernateStremio();
        boolean isStremioRunning();
        long getStremioRemainingSeconds();
    }

    private final Context context;
    private final PushToTvListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    public PushToTvServer(Context context, PushToTvListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        executor.execute(this::runServer);
        registerNsdService();
    }

    public synchronized void stop() {
        isRunning = false;
        unregisterNsdService();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        serverSocket = null;
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.i(TAG, "Push-to-TV & Web Remote daemon listening on port " + PORT);
            while (isRunning) {
                Socket client = serverSocket.accept();
                executor.execute(() -> handleClient(client));
            }
        } catch (Exception e) {
            if (isRunning) {
                Log.e(TAG, "Server loop encountered error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0].toUpperCase();
            String path = parts[1];

            if ("OPTIONS".equals(method)) {
                sendResponse(out, 200, "text/plain", "");
                return;
            }

            if (path.equals("/") || path.startsWith("/share")) {
                sendResponse(out, 200, "text/html; charset=utf-8", getHtmlPayload());
            } else if (path.equals("/icon.png") || path.equals("/icon-192.png") || path.equals("/favicon.ico")) {
                try (java.io.InputStream is = context.getResources().openRawResource(R.drawable.ic_app_logo)) {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    byte[] data = new byte[4096];
                    int nRead;
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    sendBinaryResponse(out, 200, "image/png", buffer.toByteArray());
                } catch (Exception e) {
                    sendResponse(out, 404, "text/plain", "Not Found");
                }
            } else if (path.equals("/manifest.json")) {
                sendResponse(out, 200, "application/manifest+json", getManifestPayload());
            } else if (path.equals("/sw.js")) {
                sendResponse(out, 200, "application/javascript", getSwPayload());
            } else if (path.equals("/api/info")) {
                String ip = getDeviceIp();
                boolean stremio = listener != null && listener.isStremioRunning();
                long sec = listener != null ? listener.getStremioRemainingSeconds() : 0;
                boolean tvBro = isTvBroInstalled();
                String json = "{"
                        + "\"name\":\"TCL Android TV\","
                        + "\"app\":\"CleanYourTV\","
                        + "\"ip\":\"" + ip + "\","
                        + "\"port\":" + PORT + ","
                        + "\"status\":\"online\","
                        + "\"stremioRunning\":" + stremio + ","
                        + "\"stremioTimerSec\":" + sec + ","
                        + "\"tvBroInstalled\":" + tvBro
                        + "}";
                sendResponse(out, 200, "application/json", json);
            } else if (path.startsWith("/api/open?")) {
                String rawUrl = extractParam(path, "url=");
                String url = URLDecoder.decode(rawUrl, "UTF-8");
                String mode = extractParam(path, "mode=");
                if (mode.isEmpty()) mode = "tvbro";
                String title = URLDecoder.decode(extractParam(path, "title="), "UTF-8");

                final String finalUrl = url;
                final String finalMode = mode;
                final String finalTitle = title;
                mainHandler.post(() -> {
                    if (listener != null) listener.onOpenUrl(finalUrl, finalMode, finalTitle);
                });

                sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"url\":\"" + url + "\"}");
            } else if (path.startsWith("/api/key?")) {
                String code = extractParam(path, "code=").toUpperCase();
                int keyCode = mapCodeToKeyCode(code);
                if (keyCode != 0) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onDispatchKey(keyCode);
                    });
                }
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.startsWith("/api/type?")) {
                String rawText = extractParam(path, "text=");
                String text = URLDecoder.decode(rawText, "UTF-8");
                mainHandler.post(() -> {
                    if (listener != null) listener.onTypeText(text);
                });
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.startsWith("/api/stremio/hibernate")) {
                boolean success = listener != null && listener.onHibernateStremio();
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"hibernated\":" + success + "}");
            } else if (path.startsWith("/api/stremio/status")) {
                boolean running = listener != null && listener.isStremioRunning();
                long sec = listener != null ? listener.getStremioRemainingSeconds() : 0;
                sendResponse(out, 200, "application/json", "{\"running\":" + running + ",\"remainingSec\":" + sec + "}");
            } else {
                sendResponse(out, 404, "text/plain", "Not Found");
            }

        } catch (Exception e) {
            Log.w(TAG, "Client handle exception: " + e.getMessage());
        }
    }

    private String extractParam(String path, String key) {
        int idx = path.indexOf(key);
        if (idx == -1) return "";
        int start = idx + key.length();
        int end = path.indexOf('&', start);
        return end == -1 ? path.substring(start) : path.substring(start, end);
    }

    private int mapCodeToKeyCode(String code) {
        switch (code) {
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "OK": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "BACK": return KeyEvent.KEYCODE_BACK;
            case "HOME": return KeyEvent.KEYCODE_HOME;
            case "VOL_UP": return KeyEvent.KEYCODE_VOLUME_UP;
            case "VOL_DOWN": return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "MUTE": return KeyEvent.KEYCODE_VOLUME_MUTE;
            default: return 0;
        }
    }

    private void sendResponse(OutputStream out, int code, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        sendBinaryResponse(out, code, contentType, bytes);
    }

    private void sendBinaryResponse(OutputStream out, int code, String contentType, byte[] bytes) throws Exception {
        String header = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void registerNsdService() {
        try {
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName("CleanYourTV");
            serviceInfo.setServiceType("_http._tcp.");
            serviceInfo.setPort(PORT);

            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            registrationListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo info) {
                    Log.i(TAG, "mDNS active: " + info.getServiceName() + " on port " + PORT);
                }
                @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "mDNS registration failed: " + errorCode);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo arg0) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            };
            if (nsdManager != null) {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
            }
        } catch (Exception e) {
            Log.w(TAG, "NSD service registration error: " + e.getMessage());
        }
    }

    private void unregisterNsdService() {
        try {
            if (nsdManager != null && registrationListener != null) {
                nsdManager.unregisterService(registrationListener);
            }
        } catch (Exception ignored) {}
        registrationListener = null;
    }

    public String getDeviceIp() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null && wm.getConnectionInfo().getIpAddress() != 0) {
                return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
            }
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.5";
    }

    public boolean isTvBroInstalled() {
        try {
            context.getPackageManager().getPackageInfo("com.phlox.tvwebbrowser", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getManifestPayload() {
        return "{\n"
                + "  \"name\": \"CleanYourTV Remote & Push\",\n"
                + "  \"short_name\": \"Send to TV\",\n"
                + "  \"start_url\": \"/\",\n"
                + "  \"display\": \"standalone\",\n"
                + "  \"background_color\": \"#0d1117\",\n"
                + "  \"theme_color\": \"#1f6feb\",\n"
                + "  \"icons\": [\n"
                + "    {\n"
                + "      \"src\": \"/icon.png\",\n"
                + "      \"sizes\": \"192x192 512x512\",\n"
                + "      \"type\": \"image/png\"\n"
                + "    }\n"
                + "  ],\n"
                + "  \"share_target\": {\n"
                + "    \"action\": \"/share\",\n"
                + "    \"method\": \"GET\",\n"
                + "    \"params\": {\n"
                + "      \"url\": \"url\",\n"
                + "      \"text\": \"text\",\n"
                + "      \"title\": \"title\"\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }

    private String getSwPayload() {
        return "self.addEventListener('install', e => self.skipWaiting());\n"
                + "self.addEventListener('activate', e => self.clients.claim());\n"
                + "self.addEventListener('fetch', e => e.respondWith(fetch(e.request).catch(() => new Response('Offline'))));\n";
    }

    private String getHtmlPayload() {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n"
                + "  <link rel=\"manifest\" href=\"/manifest.json\">\n"
                + "  <link rel=\"icon\" type=\"image/png\" href=\"/icon.png\">\n"
                + "  <link rel=\"apple-touch-icon\" href=\"/icon.png\">\n"
                + "  <meta name=\"theme-color\" content=\"#1f6feb\">\n"
                + "  <title>CleanYourTV Remote</title>\n"
                + "  <style>\n"
                + "    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }\n"
                + "    body {\n"
                + "      margin: 0; background: #0a0d14; color: #f0f6fc; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
                + "      min-height: 100vh; display: flex; flex-direction: column; align-items: center; padding: 18px 14px 40px;\n"
                + "    }\n"
                + "    .card {\n"
                + "      width: 100%; max-width: 420px; background: rgba(22, 27, 34, 0.85); backdrop-filter: blur(24px);\n"
                + "      border: 1px solid rgba(255, 255, 255, 0.12); border-radius: 20px; padding: 20px; margin-bottom: 16px;\n"
                + "      box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);\n"
                + "    }\n"
                + "    h1 { font-size: 1.25rem; font-weight: 600; margin: 0 0 4px; display: flex; align-items: center; justify-content: space-between; }\n"
                + "    .header-logo { width: 28px; height: 28px; border-radius: 6px; margin-right: 8px; vertical-align: middle; }\n"
                + "    .badge { font-size: 0.75rem; padding: 3px 8px; border-radius: 12px; background: #238636; color: #fff; font-weight: 500; }\n"
                + "    .ip-sub { font-size: 0.82rem; color: #8b949e; margin-bottom: 16px; }\n"
                + "    input[type=\"url\"], input[type=\"text\"] {\n"
                + "      width: 100%; padding: 12px 14px; background: rgba(13, 17, 23, 0.9); border: 1px solid rgba(255, 255, 255, 0.15);\n"
                + "      border-radius: 12px; color: #fff; font-size: 0.95rem; margin-bottom: 12px; outline: none; transition: border-color 0.2s;\n"
                + "    }\n"
                + "    input:focus { border-color: #58a6ff; }\n"
                + "    .btn {\n"
                + "      display: inline-flex; align-items: center; justify-content: center; width: 100%; padding: 12px;\n"
                + "      border-radius: 12px; font-size: 0.95rem; font-weight: 600; cursor: pointer; border: none; transition: opacity 0.15s, transform 0.1s;\n"
                + "    }\n"
                + "    .btn:active { transform: scale(0.98); opacity: 0.85; }\n"
                + "    .btn-primary { background: linear-gradient(135deg, #1f6feb, #238636); color: #fff; }\n"
                + "    .btn-secondary { background: rgba(255, 255, 255, 0.1); color: #f0f6fc; margin-top: 8px; border: 1px solid rgba(255, 255, 255, 0.08); }\n"
                + "    .btn-accent { background: #d29922; color: #000; font-weight: 700; margin-top: 8px; }\n"
                + "    .mode-select {\n"
                + "      display: flex; gap: 8px; margin-bottom: 12px;\n"
                + "    }\n"
                + "    .mode-chip {\n"
                + "      flex: 1; padding: 8px; text-align: center; border-radius: 10px; font-size: 0.82rem; cursor: pointer;\n"
                + "      background: rgba(255, 255, 255, 0.06); border: 1px solid rgba(255, 255, 255, 0.1); color: #8b949e;\n"
                + "    }\n"
                + "    .mode-chip.active { background: rgba(31, 111, 235, 0.3); border-color: #58a6ff; color: #fff; font-weight: 600; }\n"
                + "    /* Remote D-Pad */\n"
                + "    .dpad-grid {\n"
                + "      display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; max-width: 260px; margin: 16px auto 8px;\n"
                + "    }\n"
                + "    .dpad-btn {\n"
                + "      aspect-ratio: 1; background: rgba(255, 255, 255, 0.08); border: 1px solid rgba(255, 255, 255, 0.12);\n"
                + "      border-radius: 16px; color: #fff; font-size: 1.1rem; display: flex; align-items: center; justify-content: center;\n"
                + "      cursor: pointer; transition: background 0.15s, transform 0.1s;\n"
                + "    }\n"
                + "    .dpad-btn:active { background: rgba(255, 255, 255, 0.22); transform: scale(0.95); }\n"
                + "    .dpad-ok { background: rgba(31, 111, 235, 0.4); border-color: #58a6ff; font-weight: 700; }\n"
                + "    .sub-grid {\n"
                + "      display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; max-width: 260px; margin: 8px auto 0;\n"
                + "    }\n"
                + "    .sub-btn {\n"
                + "      padding: 10px; background: rgba(255, 255, 255, 0.06); border: 1px solid rgba(255, 255, 255, 0.1);\n"
                + "      border-radius: 12px; color: #f0f6fc; font-size: 0.85rem; font-weight: 600; cursor: pointer; text-align: center;\n"
                + "    }\n"
                + "    .sub-btn:active { background: rgba(255, 255, 255, 0.2); }\n"
                + "    .toast { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #238636; color: #fff;\n"
                + "      padding: 10px 18px; border-radius: 20px; font-size: 0.88rem; opacity: 0; pointer-events: none; transition: opacity 0.3s; z-index: 100; }\n"
                + "    .toast.show { opacity: 1; }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div class=\"card\">\n"
                + "    <h1><span><img src=\"/icon.png\" class=\"header-logo\" alt=\"Logo\">Push to TV</span><span class=\"badge\">CONNECTED</span></h1>\n"
                + "    <div class=\"ip-sub\" id=\"devInfo\">CleanYourTV &bull; TCL Android TV</div>\n"
                + "    <div class=\"mode-select\">\n"
                + "      <div class=\"mode-chip active\" id=\"modeTvBro\" onclick=\"setMode('tvbro')\">TV Bro (Ad-Block)</div>\n"
                + "      <div class=\"mode-chip\" id=\"modeDirect\" onclick=\"setMode('direct')\">Direct Video</div>\n"
                + "    </div>\n"
                + "    <input type=\"url\" id=\"targetUrl\" placeholder=\"Paste link (football stream, web page)...\" autocomplete=\"off\">\n"
                + "    <button class=\"btn btn-primary\" onclick=\"beamUrl()\">Beam to TV Now</button>\n"
                + "    <button class=\"btn btn-accent\" id=\"btnRam\" onclick=\"hibernateStremio()\">Free Stremio RAM (~160MB)</button>\n"
                + "  </div>\n"
                + "\n"
                + "  <div class=\"card\">\n"
                + "    <h1>TV Remote</h1>\n"
                + "    <div class=\"dpad-grid\">\n"
                + "      <div></div>\n"
                + "      <div class=\"dpad-btn\" onclick=\"sendKey('UP')\">&uarr;</div>\n"
                + "      <div></div>\n"
                + "      <div class=\"dpad-btn\" onclick=\"sendKey('LEFT')\">&larr;</div>\n"
                + "      <div class=\"dpad-btn dpad-ok\" onclick=\"sendKey('OK')\">OK</div>\n"
                + "      <div class=\"dpad-btn\" onclick=\"sendKey('RIGHT')\">&rarr;</div>\n"
                + "      <div></div>\n"
                + "      <div class=\"dpad-btn\" onclick=\"sendKey('DOWN')\">&darr;</div>\n"
                + "      <div></div>\n"
                + "    </div>\n"
                + "    <div class=\"sub-grid\">\n"
                + "      <div class=\"sub-btn\" onclick=\"sendKey('BACK')\">Back</div>\n"
                + "      <div class=\"sub-btn\" onclick=\"sendKey('HOME')\">Home</div>\n"
                + "      <div class=\"sub-btn\" onclick=\"sendKey('MUTE')\">Mute</div>\n"
                + "      <div class=\"sub-btn\" onclick=\"sendKey('VOL_DOWN')\">Vol -</div>\n"
                + "      <div class=\"sub-btn\" onclick=\"sendKey('VOL_UP')\">Vol +</div>\n"
                + "      <div class=\"sub-btn\" onclick=\"promptType()\">Keyboard</div>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "\n"
                + "  <div class=\"toast\" id=\"toast\"></div>\n"
                + "\n"
                + "  <script>\n"
                + "    let currentMode = 'tvbro';\n"
                + "    function setMode(m) {\n"
                + "      currentMode = m;\n"
                + "      document.getElementById('modeTvBro').className = 'mode-chip' + (m === 'tvbro' ? ' active' : '');\n"
                + "      document.getElementById('modeDirect').className = 'mode-chip' + (m === 'direct' ? ' active' : '');\n"
                + "    }\n"
                + "    function showToast(msg) {\n"
                + "      const t = document.getElementById('toast');\n"
                + "      t.textContent = msg; t.className = 'toast show';\n"
                + "      setTimeout(() => t.className = 'toast', 2500);\n"
                + "    }\n"
                + "    function beamUrl() {\n"
                + "      const url = document.getElementById('targetUrl').value.trim();\n"
                + "      if (!url) { showToast('Please enter a URL'); return; }\n"
                + "      fetch('/api/open?url=' + encodeURIComponent(url) + '&mode=' + currentMode)\n"
                + "        .then(r => r.json())\n"
                + "        .then(() => { showToast('Beamed to TV Bro!'); })\n"
                + "        .catch(e => showToast('Failed: ' + e));\n"
                + "    }\n"
                + "    function sendKey(code) {\n"
                + "      navigator.vibrate && navigator.vibrate(25);\n"
                + "      fetch('/api/key?code=' + code);\n"
                + "    }\n"
                + "    function promptType() {\n"
                + "      const t = prompt('Type text on TV:');\n"
                + "      if (t) fetch('/api/type?text=' + encodeURIComponent(t));\n"
                + "    }\n"
                + "    function hibernateStremio() {\n"
                + "      fetch('/api/stremio/hibernate')\n"
                + "        .then(r => r.json())\n"
                + "        .then(d => { showToast(d.hibernated ? 'Stremio killed! RAM freed.' : 'Stremio not running'); })\n"
                + "        .catch(e => showToast('Error: ' + e));\n"
                + "    }\n"
                + "    // Handle Android Web Share Target params\n"
                + "    const params = new URLSearchParams(window.location.search);\n"
                + "    const shared = params.get('url') || params.get('text');\n"
                + "    if (shared) {\n"
                + "      const match = shared.match(/https?:\\/\\/[^\\s]+/);\n"
                + "      const target = match ? match[0] : shared;\n"
                + "      document.getElementById('targetUrl').value = target;\n"
                + "      beamUrl();\n"
                + "    }\n"
                + "    if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>\n";
    }
}

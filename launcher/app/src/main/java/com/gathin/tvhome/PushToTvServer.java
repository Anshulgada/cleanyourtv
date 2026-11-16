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
        void onShelfUpdated();
        void onToggleShelfPosition();
        boolean isShelvesAboveApps();
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
            } else if (path.startsWith("/api/stremio/sync?")) {
                String rawKey = extractParam(path, "authKey=");
                String authKey = URLDecoder.decode(rawKey, "UTF-8");
                executor.execute(() -> {
                    boolean success = MediaShelfManager.syncStremioCloud(context, authKey);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onShelfUpdated();
                    });
                });
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.startsWith("/api/stremio/login?")) {
                String email = URLDecoder.decode(extractParam(path, "email="), "UTF-8");
                String pass = URLDecoder.decode(extractParam(path, "pass="), "UTF-8");
                executor.execute(() -> {
                    boolean success = MediaShelfManager.loginAndSyncStremio(context, email, pass);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onShelfUpdated();
                    });
                });
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.startsWith("/api/shelves/toggle_order")) {
                boolean target = true;
                if (listener != null) {
                    target = !listener.isShelvesAboveApps();
                    mainHandler.post(() -> listener.onToggleShelfPosition());
                }
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"above\":" + target + "}");
            } else if (path.startsWith("/api/shelves/status")) {
                boolean above = listener != null && listener.isShelvesAboveApps();
                sendResponse(out, 200, "application/json", "{\"above\":" + above + "}");
            } else if (path.startsWith("/api/open_app?")) {
                String pkg = extractParam(path, "pkg=");
                if (!pkg.isEmpty()) {
                    mainHandler.post(() -> {
                        try {
                            android.content.Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                            if (launch != null) {
                                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(launch);
                            }
                        } catch (Exception ignored) {}
                    });
                }
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.equals("/api/yt/feeds")) {
                List<String> feeds = MediaShelfManager.getCustomFeeds(context);
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String f : feeds) arr.put(f);
                sendResponse(out, 200, "application/json", arr.toString());
            } else if (path.startsWith("/api/yt/add?")) {
                String rawFeed = extractParam(path, "feed=");
                String feed = URLDecoder.decode(rawFeed, "UTF-8");
                MediaShelfManager.addCustomFeed(context, feed);
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.startsWith("/api/yt/remove?")) {
                String rawFeed = extractParam(path, "feed=");
                String feed = URLDecoder.decode(rawFeed, "UTF-8");
                MediaShelfManager.removeCustomFeed(context, feed);
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } else if (path.startsWith("/api/yt/refresh")) {
                executor.execute(() -> {
                    MediaShelfManager.refreshSmartTubeFeed(context);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onShelfUpdated();
                    });
                });
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
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
            case "MENU": return KeyEvent.KEYCODE_MENU;
            case "SETTINGS": return KeyEvent.KEYCODE_SETTINGS;
            case "VOL_UP": return KeyEvent.KEYCODE_VOLUME_UP;
            case "VOL_DOWN": return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "MUTE": return KeyEvent.KEYCODE_VOLUME_MUTE;
            case "PLAY_PAUSE": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "PLAY": return KeyEvent.KEYCODE_MEDIA_PLAY;
            case "PAUSE": return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "REWIND": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "FAST_FORWARD": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case "POWER": return KeyEvent.KEYCODE_POWER;
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
                + "  \"name\": \"CleanYourTV Remote\",\n"
                + "  \"short_name\": \"TV Remote\",\n"
                + "  \"start_url\": \"/\",\n"
                + "  \"scope\": \"/\",\n"
                + "  \"display\": \"standalone\",\n"
                + "  \"orientation\": \"portrait\",\n"
                + "  \"background_color\": \"#0a0d14\",\n"
                + "  \"theme_color\": \"#0a84ff\",\n"
                + "  \"description\": \"Tactile TV Remote & Dynamic Controller for CleanYourTV\",\n"
                + "  \"icons\": [\n"
                + "    {\n"
                + "      \"src\": \"/icon.png\",\n"
                + "      \"sizes\": \"192x192 512x512\",\n"
                + "      \"type\": \"image/png\",\n"
                + "      \"purpose\": \"any maskable\"\n"
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
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover\">\n"
                + "  <meta name=\"mobile-web-app-capable\" content=\"yes\">\n"
                + "  <meta name=\"apple-mobile-web-app-capable\" content=\"yes\">\n"
                + "  <meta name=\"apple-mobile-web-app-status-bar-style\" content=\"black-translucent\">\n"
                + "  <meta name=\"apple-mobile-web-app-title\" content=\"TV Remote\">\n"
                + "  <meta name=\"theme-color\" content=\"#0a84ff\">\n"
                + "  <link rel=\"manifest\" href=\"/manifest.json\">\n"
                + "  <link rel=\"icon\" type=\"image/png\" href=\"/icon.png\">\n"
                + "  <link rel=\"apple-touch-icon\" href=\"/icon.png\">\n"
                + "  <title>CleanYourTV Remote</title>\n"
                + "  <style>\n"
                + "    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; user-select: none; }\n"
                + "    body {\n"
                + "      margin: 0; background: #07090e; color: #f0f6fc;\n"
                + "      font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', Roboto, 'Segoe UI', sans-serif;\n"
                + "      min-height: 100vh; display: flex; flex-direction: column; align-items: center;\n"
                + "      padding: max(16px, env(safe-area-inset-top)) 14px max(32px, env(safe-area-inset-bottom));\n"
                + "    }\n"
                + "    .remote-wrap { width: 100%; max-width: 380px; display: flex; flex-direction: column; gap: 14px; }\n"
                + "    .card {\n"
                + "      background: rgba(22, 27, 34, 0.88); backdrop-filter: blur(28px); -webkit-backdrop-filter: blur(28px);\n"
                + "      border: 1px solid rgba(255, 255, 255, 0.12); border-radius: 22px; padding: 18px;\n"
                + "      box-shadow: 0 14px 36px rgba(0, 0, 0, 0.55);\n"
                + "    }\n"
                + "    .header-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }\n"
                + "    .title { font-size: 1.15rem; font-weight: 700; display: flex; align-items: center; gap: 8px; margin: 0; }\n"
                + "    .title img { width: 26px; height: 26px; border-radius: 6px; }\n"
                + "    .badge-live { font-size: 0.72rem; padding: 3px 8px; border-radius: 12px; background: #238636; color: #fff; font-weight: 600; letter-spacing: 0.03em; }\n"
                + "    .pwa-banner {\n"
                + "      display: none; background: linear-gradient(135deg, #0a84ff, #5e5ce6); border-radius: 16px; padding: 12px 16px;\n"
                + "      color: #fff; font-weight: 600; font-size: 0.88rem; text-align: center; cursor: pointer; box-shadow: 0 6px 18px rgba(10, 132, 255, 0.4);\n"
                + "    }\n"
                + "    /* Physical Remote Style */\n"
                + "    .remote-chassis {\n"
                + "      background: linear-gradient(180deg, rgba(30, 36, 48, 0.95), rgba(18, 22, 30, 0.98));\n"
                + "      border: 1px solid rgba(255, 255, 255, 0.14); border-radius: 28px; padding: 20px 16px;\n"
                + "    }\n"
                + "    .top-controls { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 18px; }\n"
                + "    .r-btn {\n"
                + "      background: rgba(255, 255, 255, 0.08); border: 1px solid rgba(255, 255, 255, 0.12);\n"
                + "      border-radius: 14px; padding: 10px; color: #fff; font-weight: 600; font-size: 0.88rem;\n"
                + "      display: flex; align-items: center; justify-content: center; cursor: pointer;\n"
                + "      transition: transform 0.08s, background 0.15s;\n"
                + "    }\n"
                + "    .r-btn:active { transform: scale(0.93); background: rgba(255, 255, 255, 0.22); }\n"
                + "    .r-btn.power { background: rgba(248, 81, 73, 0.2); border-color: rgba(248, 81, 73, 0.4); color: #ff7b72; }\n"
                + "    /* Circular Tactical D-Pad */\n"
                + "    .dpad-disk {\n"
                + "      width: 210px; height: 210px; margin: 6px auto 18px; position: relative;\n"
                + "      background: rgba(255, 255, 255, 0.04); border: 2px solid rgba(255, 255, 255, 0.12);\n"
                + "      border-radius: 50%; box-shadow: inset 0 2px 8px rgba(0,0,0,0.4), 0 8px 24px rgba(0,0,0,0.5);\n"
                + "    }\n"
                + "    .dp-dir {\n"
                + "      position: absolute; display: flex; align-items: center; justify-content: center;\n"
                + "      color: #fff; font-size: 1.3rem; font-weight: bold; cursor: pointer;\n"
                + "    }\n"
                + "    .dp-dir:active { color: #58a6ff; transform: scale(0.95); }\n"
                + "    .dp-up { top: 6px; left: 50%; transform: translateX(-50%); width: 70px; height: 50px; }\n"
                + "    .dp-down { bottom: 6px; left: 50%; transform: translateX(-50%); width: 70px; height: 50px; }\n"
                + "    .dp-left { left: 6px; top: 50%; transform: translateY(-50%); width: 50px; height: 70px; }\n"
                + "    .dp-right { right: 6px; top: 50%; transform: translateY(-50%); width: 50px; height: 70px; }\n"
                + "    .dp-ok {\n"
                + "      position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);\n"
                + "      width: 82px; height: 82px; border-radius: 50%;\n"
                + "      background: linear-gradient(135deg, #1f6feb, #0a84ff); border: 2px solid rgba(255, 255, 255, 0.25);\n"
                + "      color: #fff; font-weight: 800; font-size: 1.1rem; display: flex; align-items: center; justify-content: center;\n"
                + "      box-shadow: 0 4px 14px rgba(10, 132, 255, 0.45); cursor: pointer;\n"
                + "    }\n"
                + "    .dp-ok:active { transform: translate(-50%, -50%) scale(0.92); opacity: 0.9; }\n"
                + "    /* Navigation Cluster */\n"
                + "    .nav-cluster { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 14px; }\n"
                + "    .rocker-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 14px; }\n"
                + "    .rocker-box { display: flex; flex-direction: column; gap: 6px; background: rgba(0,0,0,0.25); padding: 8px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.08); }\n"
                + "    .rocker-lbl { font-size: 0.72rem; color: #8b949e; text-align: center; text-transform: uppercase; font-weight: 700; }\n"
                + "    .media-cluster { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }\n"
                + "    /* App Quick Launchers */\n"
                + "    .app-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }\n"
                + "    .app-chip {\n"
                + "      padding: 10px 8px; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12);\n"
                + "      border-radius: 14px; text-align: center; font-size: 0.84rem; font-weight: 600; color: #fff; cursor: pointer;\n"
                + "    }\n"
                + "    .app-chip:active { background: rgba(255,255,255,0.18); transform: scale(0.95); }\n"
                + "    /* YouTube Feeds Manager */\n"
                + "    .feed-tags { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }\n"
                + "    .feed-tag {\n"
                + "      display: inline-flex; align-items: center; gap: 6px; padding: 5px 10px; border-radius: 12px;\n"
                + "      background: rgba(88, 166, 255, 0.15); border: 1px solid rgba(88, 166, 255, 0.3); color: #58a6ff; font-size: 0.8rem; font-weight: 600;\n"
                + "    }\n"
                + "    .feed-tag span.del { cursor: pointer; color: #f85149; font-weight: bold; padding: 0 2px; }\n"
                + "    input[type=\"url\"], input[type=\"text\"], input[type=\"password\"] {\n"
                + "      width: 100%; padding: 11px 13px; background: rgba(13, 17, 23, 0.95); border: 1px solid rgba(255, 255, 255, 0.14);\n"
                + "      border-radius: 12px; color: #fff; font-size: 0.92rem; margin-bottom: 10px; outline: none;\n"
                + "    }\n"
                + "    input:focus { border-color: #58a6ff; }\n"
                + "    .btn {\n"
                + "      display: inline-flex; align-items: center; justify-content: center; width: 100%; padding: 11px;\n"
                + "      border-radius: 12px; font-size: 0.92rem; font-weight: 600; cursor: pointer; border: none;\n"
                + "      transition: opacity 0.15s, transform 0.08s;\n"
                + "    }\n"
                + "    .btn:active { transform: scale(0.97); opacity: 0.85; }\n"
                + "    .btn-primary { background: linear-gradient(135deg, #0a84ff, #238636); color: #fff; }\n"
                + "    .btn-secondary { background: rgba(255, 255, 255, 0.09); color: #f0f6fc; border: 1px solid rgba(255, 255, 255, 0.1); margin-top: 6px; }\n"
                + "    .btn-accent { background: #d29922; color: #000; font-weight: 700; }\n"
                + "    .mode-select { display: flex; gap: 8px; margin-bottom: 10px; }\n"
                + "    .mode-chip {\n"
                + "      flex: 1; padding: 7px; text-align: center; border-radius: 10px; font-size: 0.8rem; cursor: pointer;\n"
                + "      background: rgba(255, 255, 255, 0.06); border: 1px solid rgba(255, 255, 255, 0.1); color: #8b949e;\n"
                + "    }\n"
                + "    .mode-chip.active { background: rgba(31, 111, 235, 0.35); border-color: #58a6ff; color: #fff; font-weight: 600; }\n"
                + "    .toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%); background: #238636; color: #fff;\n"
                + "      padding: 9px 18px; border-radius: 20px; font-size: 0.85rem; opacity: 0; pointer-events: none; transition: opacity 0.25s; z-index: 200; }\n"
                + "    .toast.show { opacity: 1; }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div class=\"remote-wrap\">\n"
                + "    <!-- PWA Install Banner -->\n"
                + "    <div class=\"pwa-banner\" id=\"pwaBanner\" onclick=\"installPwa()\">📲 Add Remote App to Home Screen</div>\n"
                + "\n"
                + "    <!-- Physical TV Remote -->\n"
                + "    <div class=\"remote-chassis\">\n"
                + "      <div class=\"header-row\">\n"
                + "        <div class=\"title\"><img src=\"/icon.png\" alt=\"Logo\">TV Remote</div>\n"
                + "        <div class=\"badge-live\" id=\"statusBadge\">192.168.1.5</div>\n"
                + "      </div>\n"
                + "\n"
                + "      <div class=\"top-controls\">\n"
                + "        <div class=\"r-btn power\" onclick=\"sendKey('POWER')\">⏻ Power</div>\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('MUTE')\">🔇 Mute</div>\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('SETTINGS')\">⚙ Settings</div>\n"
                + "      </div>\n"
                + "\n"
                + "      <!-- Tactile Circular D-Pad -->\n"
                + "      <div class=\"dpad-disk\">\n"
                + "        <div class=\"dp-dir dp-up\" onclick=\"sendKey('UP')\">▲</div>\n"
                + "        <div class=\"dp-dir dp-left\" onclick=\"sendKey('LEFT')\">◀</div>\n"
                + "        <div class=\"dp-ok\" onclick=\"sendKey('OK')\">OK</div>\n"
                + "        <div class=\"dp-dir dp-right\" onclick=\"sendKey('RIGHT')\">▶</div>\n"
                + "        <div class=\"dp-dir dp-down\" onclick=\"sendKey('DOWN')\">▼</div>\n"
                + "      </div>\n"
                + "\n"
                + "      <!-- Navigation Cluster -->\n"
                + "      <div class=\"nav-cluster\">\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('BACK')\">↩ Back</div>\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('HOME')\">⌂ Home</div>\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('MENU')\">☰ Menu</div>\n"
                + "      </div>\n"
                + "\n"
                + "      <!-- Rocker Controls -->\n"
                + "      <div class=\"rocker-row\">\n"
                + "        <div class=\"rocker-box\">\n"
                + "          <div class=\"rocker-lbl\">Volume</div>\n"
                + "          <div class=\"r-btn\" onclick=\"sendKey('VOL_UP')\">Vol +</div>\n"
                + "          <div class=\"r-btn\" onclick=\"sendKey('VOL_DOWN')\">Vol -</div>\n"
                + "        </div>\n"
                + "        <div class=\"rocker-box\">\n"
                + "          <div class=\"rocker-lbl\">Type & Info</div>\n"
                + "          <div class=\"r-btn\" onclick=\"promptType()\">⌨ Type Text</div>\n"
                + "          <div class=\"r-btn\" onclick=\"sendKey('PLAY_PAUSE')\">⏯ Play/Pause</div>\n"
                + "        </div>\n"
                + "      </div>\n"
                + "\n"
                + "      <!-- Media Controls -->\n"
                + "      <div class=\"media-cluster\">\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('REWIND')\">⏮ Rewind</div>\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('PLAY_PAUSE')\">⏯ Toggle</div>\n"
                + "        <div class=\"r-btn\" onclick=\"sendKey('FAST_FORWARD')\">⏭ Fwd</div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- Quick App Switcher -->\n"
                + "    <div class=\"card\">\n"
                + "      <div class=\"header-row\"><div class=\"title\">Quick Launch</div></div>\n"
                + "      <div class=\"app-grid\">\n"
                + "        <div class=\"app-chip\" onclick=\"launchApp('com.stremio.one')\">Stremio</div>\n"
                + "        <div class=\"app-chip\" onclick=\"launchApp('org.smarttube.stable')\">SmartTube</div>\n"
                + "        <div class=\"app-chip\" onclick=\"launchApp('com.phlox.tvwebbrowser')\">TV Bro</div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- TV Performance & Shelf Layout Order -->\n"
                + "    <div class=\"card\">\n"
                + "      <div class=\"header-row\"><div class=\"title\">TV Performance & Shelves</div></div>\n"
                + "      <button class=\"btn btn-accent\" onclick=\"hibernateStremio()\">⚡ Free Stremio RAM (~160MB)</button>\n"
                + "      <div style=\"font-size:0.8rem; color:#8b949e; margin:12px 0 6px;\" id=\"shelfSub\">Shelf Position: Below All Apps</div>\n"
                + "      <button class=\"btn btn-secondary\" onclick=\"toggleShelfOrder()\">Toggle: Below Dock vs Below Apps</button>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- Dynamic YouTube Feeds -->\n"
                + "    <div class=\"card\">\n"
                + "      <div class=\"header-row\"><div class=\"title\">Dynamic YouTube Feeds</div></div>\n"
                + "      <div style=\"font-size:0.8rem; color:#8b949e;\">Active channels & topics on TV home shelf:</div>\n"
                + "      <div class=\"feed-tags\" id=\"feedTagsList\"></div>\n"
                + "      <div style=\"display:flex; gap:6px;\">\n"
                + "        <input type=\"text\" id=\"newFeedInput\" placeholder=\"e.g. @FCBarcelona, @mkbhd, trending\" style=\"margin-bottom:0;\">\n"
                + "        <button class=\"btn btn-primary\" style=\"width:auto; padding:0 16px; white-space:nowrap;\" onclick=\"addNewFeed()\">+ Add</button>\n"
                + "      </div>\n"
                + "      <button class=\"btn btn-secondary\" style=\"margin-top:8px;\" onclick=\"refreshYtFeeds()\">↻ Refresh Dynamic Videos Now</button>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- Stremio Continue Watching -->\n"
                + "    <div class=\"card\">\n"
                + "      <div class=\"header-row\"><div class=\"title\">Stremio Continue Watching</div></div>\n"
                + "      <div style=\"font-size:0.8rem; color:#8b949e; margin-bottom:10px;\">Sync real Stremio library to TV home shelf</div>\n"
                + "      <input type=\"text\" id=\"stremioEmail\" placeholder=\"Stremio Email...\">\n"
                + "      <input type=\"password\" id=\"stremioPass\" placeholder=\"Stremio Password...\">\n"
                + "      <button class=\"btn btn-primary\" onclick=\"loginStremio()\">Log In & Sync Shelf</button>\n"
                + "      <div style=\"font-size:0.8rem; color:#8b949e; margin:10px 0 6px;\">Or manual AuthKey:</div>\n"
                + "      <div style=\"display:flex; gap:6px;\">\n"
                + "        <input type=\"text\" id=\"stremioKey\" placeholder=\"Paste AuthKey...\" style=\"margin-bottom:0;\">\n"
                + "        <button class=\"btn btn-secondary\" style=\"width:auto; padding:0 16px; white-space:nowrap; margin-top:0;\" onclick=\"syncStremioKey()\">Sync</button>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- Push Stream Link to TV -->\n"
                + "    <div class=\"card\">\n"
                + "      <div class=\"header-row\"><div class=\"title\">Push Link to TV</div></div>\n"
                + "      <div class=\"mode-select\">\n"
                + "        <div class=\"mode-chip active\" id=\"modeTvBro\" onclick=\"setMode('tvbro')\">TV Bro (Ad-Block)</div>\n"
                + "        <div class=\"mode-chip\" id=\"modeDirect\" onclick=\"setMode('direct')\">Direct Player</div>\n"
                + "      </div>\n"
                + "      <input type=\"url\" id=\"targetUrl\" placeholder=\"Paste match or stream URL...\">\n"
                + "      <button class=\"btn btn-primary\" onclick=\"beamUrl()\">Beam to TV Now</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "\n"
                + "  <div class=\"toast\" id=\"toast\"></div>\n"
                + "\n"
                + "  <script>\n"
                + "    let deferredPrompt = null;\n"
                + "    window.addEventListener('beforeinstallprompt', (e) => {\n"
                + "      e.preventDefault();\n"
                + "      deferredPrompt = e;\n"
                + "      const banner = document.getElementById('pwaBanner');\n"
                + "      if (banner) banner.style.display = 'block';\n"
                + "    });\n"
                + "    function installPwa() {\n"
                + "      if (deferredPrompt) {\n"
                + "        deferredPrompt.prompt();\n"
                + "        deferredPrompt.userChoice.then(() => {\n"
                + "          document.getElementById('pwaBanner').style.display = 'none';\n"
                + "          deferredPrompt = null;\n"
                + "        });\n"
                + "      } else {\n"
                + "        showToast('Tap browser menu (⋮) -> Add to Home Screen');\n"
                + "      }\n"
                + "    }\n"
                + "    let currentMode = 'tvbro';\n"
                + "    function setMode(m) {\n"
                + "      currentMode = m;\n"
                + "      document.getElementById('modeTvBro').className = 'mode-chip' + (m === 'tvbro' ? ' active' : '');\n"
                + "      document.getElementById('modeDirect').className = 'mode-chip' + (m === 'direct' ? ' active' : '');\n"
                + "    }\n"
                + "    function showToast(msg) {\n"
                + "      const t = document.getElementById('toast');\n"
                + "      t.textContent = msg; t.className = 'toast show';\n"
                + "      setTimeout(() => t.className = 'toast', 2400);\n"
                + "    }\n"
                + "    function sendKey(code) {\n"
                + "      navigator.vibrate && navigator.vibrate(20);\n"
                + "      fetch('/api/key?code=' + code);\n"
                + "    }\n"
                + "    function launchApp(pkg) {\n"
                + "      navigator.vibrate && navigator.vibrate(20);\n"
                + "      fetch('/api/open_app?pkg=' + encodeURIComponent(pkg))\n"
                + "        .then(() => showToast('Launching app...'))\n"
                + "        .catch(e => showToast('Error: ' + e));\n"
                + "    }\n"
                + "    function promptType() {\n"
                + "      const t = prompt('Type text on TV:');\n"
                + "      if (t) fetch('/api/type?text=' + encodeURIComponent(t));\n"
                + "    }\n"
                + "    function beamUrl() {\n"
                + "      const url = document.getElementById('targetUrl').value.trim();\n"
                + "      if (!url) { showToast('Please enter a URL'); return; }\n"
                + "      fetch('/api/open?url=' + encodeURIComponent(url) + '&mode=' + currentMode)\n"
                + "        .then(r => r.json())\n"
                + "        .then(() => showToast('Beamed to TV!'))\n"
                + "        .catch(e => showToast('Failed: ' + e));\n"
                + "    }\n"
                + "    function hibernateStremio() {\n"
                + "      fetch('/api/stremio/hibernate')\n"
                + "        .then(r => r.json())\n"
                + "        .then(d => showToast(d.hibernated ? 'Stremio killed! RAM freed.' : 'Stremio not running'))\n"
                + "        .catch(e => showToast('Error: ' + e));\n"
                + "    }\n"
                + "    function toggleShelfOrder() {\n"
                + "      fetch('/api/shelves/toggle_order')\n"
                + "        .then(r => r.json())\n"
                + "        .then(d => {\n"
                + "          showToast('Shelf order updated on TV!');\n"
                + "          updateShelfStatus();\n"
                + "        })\n"
                + "        .catch(e => showToast('Toggle failed: ' + e));\n"
                + "    }\n"
                + "    function updateShelfStatus() {\n"
                + "      fetch('/api/shelves/status')\n"
                + "        .then(r => r.json())\n"
                + "        .then(d => {\n"
                + "          const sub = document.getElementById('shelfSub');\n"
                + "          if (sub) sub.textContent = d.above ? 'Shelf Position: Below Dock (Above Apps)' : 'Shelf Position: Below All Apps';\n"
                + "        });\n"
                + "    }\n"
                + "    function loadYtFeeds() {\n"
                + "      fetch('/api/yt/feeds')\n"
                + "        .then(r => r.json())\n"
                + "        .then(feeds => {\n"
                + "          const box = document.getElementById('feedTagsList');\n"
                + "          box.innerHTML = '';\n"
                + "          feeds.forEach(f => {\n"
                + "            const tag = document.createElement('div');\n"
                + "            tag.className = 'feed-tag';\n"
                + "            tag.innerHTML = f + ' <span class=\"del\" onclick=\"removeFeed(\\'' + f + '\\')\">✕</span>';\n"
                + "            box.appendChild(tag);\n"
                + "          });\n"
                + "        });\n"
                + "    }\n"
                + "    function addNewFeed() {\n"
                + "      const inp = document.getElementById('newFeedInput');\n"
                + "      const val = inp.value.trim();\n"
                + "      if (!val) return;\n"
                + "      fetch('/api/yt/add?feed=' + encodeURIComponent(val))\n"
                + "        .then(() => {\n"
                + "          inp.value = '';\n"
                + "          showToast('Added feed: ' + val);\n"
                + "          loadYtFeeds();\n"
                + "        });\n"
                + "    }\n"
                + "    function removeFeed(f) {\n"
                + "      fetch('/api/yt/remove?feed=' + encodeURIComponent(f))\n"
                + "        .then(() => {\n"
                + "          showToast('Removed feed: ' + f);\n"
                + "          loadYtFeeds();\n"
                + "        });\n"
                + "    }\n"
                + "    function refreshYtFeeds() {\n"
                + "      showToast('Refreshing YouTube dynamic shelf...');\n"
                + "      fetch('/api/yt/refresh')\n"
                + "        .then(() => showToast('Dynamic feeds refreshed!'));\n"
                + "    }\n"
                + "    function loginStremio() {\n"
                + "      const email = document.getElementById('stremioEmail').value.trim();\n"
                + "      const pass = document.getElementById('stremioPass').value.trim();\n"
                + "      if (!email || !pass) { showToast('Please enter both Email and Password'); return; }\n"
                + "      showToast('Logging in and syncing...');\n"
                + "      fetch('/api/stremio/login?email=' + encodeURIComponent(email) + '&pass=' + encodeURIComponent(pass))\n"
                + "        .then(r => r.json())\n"
                + "        .then(() => showToast('Stremio library synced!'))\n"
                + "        .catch(e => showToast('Login failed: ' + e));\n"
                + "    }\n"
                + "    function syncStremioKey() {\n"
                + "      const key = document.getElementById('stremioKey').value.trim();\n"
                + "      if (!key) { showToast('Please enter AuthKey'); return; }\n"
                + "      showToast('Syncing with Stremio...');\n"
                + "      fetch('/api/stremio/sync?authKey=' + encodeURIComponent(key))\n"
                + "        .then(r => r.json())\n"
                + "        .then(() => showToast('Continue Watching synced!'))\n"
                + "        .catch(e => showToast('Sync failed: ' + e));\n"
                + "    }\n"
                + "    updateShelfStatus();\n"
                + "    loadYtFeeds();\n"
                + "    if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>\n";
    }
}

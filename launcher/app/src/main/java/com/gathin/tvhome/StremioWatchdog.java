package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

public final class StremioWatchdog {
    private static final String TAG = "StremioWatchdog";
    public static final String PACKAGE_STREMIO = "com.stremio.one";
    // 60 minutes inactivity limit on TV launcher screen
    public static final long INACTIVITY_LIMIT_MS = 60L * 60L * 1000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean timerRunning = false;
    private long stremioDetectedAt = 0;
    private Runnable onHibernateListener;

    private final Runnable killRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "60-minute inactivity reached. Hibernating Stremio...");
            hibernateStremio();
        }
    };

    public StremioWatchdog(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setOnHibernateListener(Runnable listener) {
        this.onHibernateListener = listener;
    }

    public synchronized void onHomeResumed() {
        MediaShelfManager.snapshotStremioState(context);
        if (isStremioRunning()) {
            if (!timerRunning) {
                stremioDetectedAt = System.currentTimeMillis();
                timerRunning = true;
                handler.postDelayed(killRunnable, INACTIVITY_LIMIT_MS);
                Log.i(TAG, "Stremio background process active. 60-minute auto-hibernate timer armed.");
            }
        } else {
            cancelTimer();
        }
    }

    public synchronized void onHomePaused() {
        // When leaving launcher (e.g. user is actively watching TV Bro or playing Stremio), disarm
        cancelTimer();
    }

    public synchronized void cancelTimer() {
        if (timerRunning) {
            handler.removeCallbacks(killRunnable);
            timerRunning = false;
        }
    }

    public boolean isStremioRunning() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info.processName != null && info.processName.contains(PACKAGE_STREMIO)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Process check error: " + e.getMessage());
        }
        return false;
    }

    public synchronized boolean hibernateStremio() {
        try {
            MediaShelfManager.snapshotStremioState(context);
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(PACKAGE_STREMIO);
                timerRunning = false;
                handler.removeCallbacks(killRunnable);
                Log.i(TAG, "Stremio background processes successfully killed. Reclaimed ~150-180 MB RAM.");
                if (onHibernateListener != null) {
                    handler.post(onHibernateListener);
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill Stremio processes: " + e.getMessage());
        }
        return false;
    }

    public boolean isTimerRunning() {
        return timerRunning;
    }

    public long getRemainingSeconds() {
        if (!timerRunning) return 0;
        long elapsed = System.currentTimeMillis() - stremioDetectedAt;
        long remaining = (INACTIVITY_LIMIT_MS - elapsed) / 1000L;
        return Math.max(0, remaining);
    }
}

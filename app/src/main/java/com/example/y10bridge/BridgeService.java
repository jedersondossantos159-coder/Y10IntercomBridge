package com.example.y10bridge;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeService extends Service {
    private static final String CH = "y10_bridge_v2";
    private static final int ID = 202;

    private AudioManager am;
    private AudioRecord rec;
    private AudioTrack play;
    private PowerManager.WakeLock wl;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<AudioDeviceInfo> comm = new ArrayList<>();
    private final List<AudioDeviceInfo> outs = new ArrayList<>();

    @Override public void onCreate() {
        super.onCreate();
        am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CH, "Y10 Intercom V2", NotificationManager.IMPORTANCE_LOW));
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y10Bridge:Audio");
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        startForeground(ID, notification("Intercom ativo"));
        if (!wl.isHeld()) wl.acquire();

        int input = in.getIntExtra("input", 0);
        int output = in.getIntExtra("output", -1);
        int mode = in.getIntExtra("mode", 0);
        int gain = in.getIntExtra("gain", 100);
        int vox = in.getIntExtra("vox", 20);

        running.set(false);
        cleanup();
        new Thread(() -> bridge(input, output, mode, gain, vox), "Y10BridgeV2").start();
        return START_STICKY;
    }

    private void scan() {
        comm.clear(); outs.clear();
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) comm.add(d);
                }
            } catch (Exception ignored) {}
        }
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                t == AudioDeviceInfo.TYPE_BLE_HEADSET) outs.add(d);
        }
    }

    private void bridge(int input, int output, int mode, int gain, int vox) {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            scan();

            if (Build.VERSION.SDK_INT >= 31) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                if (!comm.isEmpty()) am.setCommunicationDevice(comm.get(Math.max(0, Math.min(input, comm.size()-1))));
            } else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                Thread.sleep(1000);
            }

            int sr = 16000, enc = AudioFormat.ENCODING_PCM_16BIT;
            int bs = Math.max(4096, Math.max(
                    AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, enc),
                    AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, enc)) * 2);

            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr,
                    AudioFormat.CHANNEL_IN_MONO, enc, bs);

            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioFormat af = new AudioFormat.Builder()
                    .setEncoding(enc).setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();

            play = new AudioTrack(aa, af, bs, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (output >= 0 && output < outs.size()) play.setPreferredDevice(outs.get(output));

            rec.startRecording();
            play.play();
            running.set(true);

            short[] buf = new short[bs/2];
            int active = Math.max(0, Math.min(input, Math.max(0, comm.size()-1)));
            long silence = System.currentTimeMillis();

            while (running.get()) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;

                float g = Math.max(0f, gain / 100f);
                long e = 0;
                for (int i=0;i<n;i++) {
                    int v = (int)(buf[i]*g);
                    v = Math.max(-32768, Math.min(32767, v));
                    buf[i] = (short)v;
                    e += Math.abs(v);
                }
                int avg = (int)(e / Math.max(1,n));

                if (mode == 2 && Build.VERSION.SDK_INT >= 31 && comm.size() >= 2) {
                    long now = System.currentTimeMillis();
                    int threshold = 150 + vox*50;
                    if (avg > threshold) silence = now;
                    else if (now - silence > 650) {
                        active = (active + 1) % 2;
                        am.setCommunicationDevice(comm.get(active));
                        silence = now;
                    }
                }

                play.write(buf, 0, n, AudioTrack.WRITE_BLOCKING);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            cleanup();
            stopSelf();
        }
    }

    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("Y10 Intercom Bridge V2")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setOngoing(true)
                .setContentIntent(pi).build();
    }

    private void cleanup() {
        running.set(false);
        try { if (rec != null) rec.stop(); } catch (Exception ignored) {}
        try { if (play != null) play.stop(); } catch (Exception ignored) {}
        try { if (rec != null) rec.release(); } catch (Exception ignored) {}
        try { if (play != null) play.release(); } catch (Exception ignored) {}
        rec = null; play = null;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        cleanup();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

        running.set(false);

        cleanupAudio();

        new Thread(
                () -> runBridge(
                        inputIndex,
                        outputIndex,
                        mode,
                        gain,
                        vox
                ),
                "Y10BridgeV2"
        ).start();

        return START_STICKY;
    }

    private void refreshDevices() {

        commDevices.clear();
        outputDevices.clear();

        if (Build.VERSION.SDK_INT >= 31) {

            try {

                for (
                        AudioDeviceInfo device :
                        audioManager
                                .getAvailableCommunicationDevices()
                ) {

                    if (
                            device.getType()
                                    ==
                            Audio

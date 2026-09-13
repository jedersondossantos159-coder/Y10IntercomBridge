package com.example.y10bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeService extends Service {

    private static final String CHANNEL_ID = "y10_bridge_v3";
    private static final int NOTIFICATION_ID = 310;
    private static final String STATUS_ACTION = "com.example.y10bridge.STATUS";

    private AudioManager audioManager;
    private AudioRecord recorderA;
    private AudioRecord recorderB;
    private AudioTrack player;
    private PowerManager.WakeLock wakeLock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Y10 Intercom V3", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y10Bridge:V3");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Verificando microfones Y10..."));
        if (!wakeLock.isHeld()) wakeLock.acquire();

        final int gain = intent != null ? intent.getIntExtra("gain", 100) : 100;
        running.set(false);
        cleanupAudio();

        new Thread(() -> runBridge(gain), "Y10BridgeV3").start();
        return START_STICKY;
    }

    private void runBridge(int gain) {
        try {
            // Mantém a política de mídia que já permitiu som nos dois Y10.
            audioManager.setMode(AudioManager.MODE_NORMAL);

            List<AudioDeviceInfo> btInputs = scanBluetoothInputs();

            if (btInputs.size() < 2) {
                sendStatus(false, btInputs.size(),
                        "● Android expôs " + btInputs.size() + "/2 microfones Bluetooth. Não vou usar o microfone do celular.");
                updateNotification("Faltam microfones Bluetooth: " + btInputs.size() + "/2");
                stopSelf();
                return;
            }

            final int sampleRate = 48000;
            final int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int min = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding);
            int bufferSize = Math.max(8192, min * 2);

            recorderA = createRecorder(sampleRate, encoding, bufferSize, btInputs.get(0));
            recorderB = createRecorder(sampleRate, encoding, bufferSize, btInputs.get(1));

            if (recorderA.getState() != AudioRecord.STATE_INITIALIZED ||
                    recorderB.getState() != AudioRecord.STATE_INITIALIZED) {
                sendStatus(false, btInputs.size(),
                        "● Os 2 microfones apareceram, mas o Android não permitiu abrir ambos ao mesmo tempo.");
                stopSelf();
                return;
            }

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat outFormat = new AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();

            int outMin = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding);

            player = new AudioTrack(attrs, outFormat,
                    Math.max(bufferSize * 2, outMin * 2),
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);

            // Não seleciona saída. O HyperOS continua responsável por duplicar mídia nos dois Y10.
            recorderA.startRecording();
            recorderB.startRecording();
            player.play();
            running.set(true);

            sendStatus(true, 2, "● INTERCOM ATIVO • MIC A + MIC B • saída de mídia dupla");
            updateNotification("2 microfones Y10 ativos");

            short[] a = new short[bufferSize / 2];
            short[] b = new short[bufferSize / 2];
            short[] stereo = new short[bufferSize * 2];
            float g = Math.max(0f, gain / 100f);

            while (running.get()) {
                int na = recorderA.read(a, 0, a.length);
                int nb = recorderB.read(b, 0, b.length);
                int n = Math.max(na > 0 ? na : 0, nb > 0 ? nb : 0);
                if (n <= 0) continue;

                int p = 0;
                for (int i = 0; i < n; i++) {
                    int va = (i < na && na > 0) ? a[i] : 0;
                    int vb = (i < nb && nb > 0) ? b[i] : 0;
                    int mixed = (int) (((va + vb) / 2f) * g);
                    mixed = Math.max(-32768, Math.min(32767, mixed));
                    short s = (short) mixed;
                    stereo[p++] = s;
                    stereo[p++] = s;
                }
                player.write(stereo, 0, p, AudioTrack.WRITE_BLOCKING);
            }

        } catch (Throwable t) {
            sendStatus(false, -1, "● Erro ao iniciar intercom: " + t.getClass().getSimpleName());
        } finally {
            cleanupAudio();
        }
    }

    private AudioRecord createRecorder(int sampleRate, int encoding, int bufferSize, AudioDeviceInfo device) {
        AudioRecord r = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                encoding,
                bufferSize);
        try { r.setPreferredDevice(device); } catch (Throwable ignored) {}
        return r;
    }

    private List<AudioDeviceInfo> scanBluetoothInputs() {
        List<AudioDeviceInfo> list = new ArrayList<>();
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                list.add(d);
            }
        }
        return list;
    }

    private void sendStatus(boolean isRunning, int micCount, String message) {
        Intent i = new Intent(STATUS_ACTION);
        i.setPackage(getPackageName());
        i.putExtra("running", isRunning);
        i.putExtra("micCount", micCount);
        i.putExtra("message", message);
        sendBroadcast(i);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle("Y10 Intercom Bridge V3")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void cleanupAudio() {
        running.set(false);
        try { if (recorderA != null) recorderA.stop(); } catch (Exception ignored) {}
        try { if (recorderB != null) recorderB.stop(); } catch (Exception ignored) {}
        try { if (player != null) player.stop(); } catch (Exception ignored) {}
        try { if (recorderA != null) recorderA.release(); } catch (Exception ignored) {}
        try { if (recorderB != null) recorderB.release(); } catch (Exception ignored) {}
        try { if (player != null) player.release(); } catch (Exception ignored) {}
        recorderA = null;
        recorderB = null;
        player = null;
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        cleanupAudio();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
                              }

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
    private static final String CHANNEL_ID="y10_bridge_v4";
    private static final int NOTIFICATION_ID=410;
    private static final String STATUS_ACTION="com.example.y10bridge.STATUS";
    private AudioManager audioManager;
    private AudioRecord recorderA, recorderB;
    private AudioTrack player;
    private PowerManager.WakeLock wakeLock;
    private final AtomicBoolean running=new AtomicBoolean(false);

    @Override public void onCreate(){
        super.onCreate();
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        if(Build.VERSION.SDK_INT>=26){ NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Y10 Intercom V4",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(c); }
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE); wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Y10Bridge:V4");
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        startForeground(NOTIFICATION_ID,buildNotification("Preparando Y10..."));
        if(!wakeLock.isHeld()) wakeLock.acquire();
        int gain=intent!=null?intent.getIntExtra("gain",100):100;
        boolean experimental=intent!=null&&intent.getBooleanExtra("experimentalAB",false);
        cleanupAudio(); running.set(false);
        new Thread(() -> { if(experimental) runExperimentalAB(gain); else runDualMedia(gain); },"Y10BridgeV4").start();
        return START_STICKY;
    }

    private void runDualMedia(int gain){
        try{
            audioManager.setMode(AudioManager.MODE_NORMAL);
            List<AudioDeviceInfo> ins=scanBluetoothInputs();
            if(ins.size()<2){ sendStatus(false,ins.size(),"● Android expôs "+ins.size()+"/2 entradas BT. Dual-media continua, mas 2 microfones não estão disponíveis."); stopSelf(); return; }
            setupDualRecorders(ins.get(0),ins.get(1),gain);
        }catch(Throwable t){ sendStatus(false,-1,"● Erro Dual Media: "+t.getClass().getSimpleName()); }
        finally{ cleanupAudio(); }
    }

    private void setupDualRecorders(AudioDeviceInfo aDev,AudioDeviceInfo bDev,int gain)throws Exception{
        final int rate=48000, enc=AudioFormat.ENCODING_PCM_16BIT;
        int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,enc); int bs=Math.max(8192,min*2);
        recorderA=createRecorder(rate,enc,bs,aDev); recorderB=createRecorder(rate,enc,bs,bDev);
        if(recorderA.getState()!=AudioRecord.STATE_INITIALIZED||recorderB.getState()!=AudioRecord.STATE_INITIALIZED){ sendStatus(false,2,"● 2 entradas apareceram, mas o Android bloqueou abrir as duas ao mesmo tempo."); return; }
        player=createMediaPlayer(rate,enc,bs);
        recorderA.startRecording(); recorderB.startRecording(); player.play(); running.set(true);
        sendStatus(true,2,"● INTERCOM ATIVO • 2 MICs • saída de mídia dupla"); updateNotification("2 microfones ativos");
        short[] a=new short[bs/2], b=new short[bs/2], stereo=new short[bs*2]; float g=Math.max(0f,gain/100f);
        while(running.get()){
            int na=recorderA.read(a,0,a.length), nb=recorderB.read(b,0,b.length); int n=Math.max(Math.max(na,0),Math.max(nb,0)); if(n<=0)continue; int p=0;
            for(int i=0;i<n;i++){ int va=(na>i)?a[i]:0, vb=(nb>i)?b[i]:0; int m=(int)(((va+vb)/2f)*g); m=Math.max(-32768,Math.min(32767,m)); short s=(short)m; stereo[p++]=s; stereo[p++]=s; }
            player.write(stereo,0,p,AudioTrack.WRITE_BLOCKING);
        }
    }

    private void runExperimentalAB(int gain){
        try{
            if(Build.VERSION.SDK_INT<31){ sendStatus(false,-1,"● TESTE LIMITE requer Android 12+ para setCommunicationDevice()."); return; }
            List<AudioDeviceInfo> comm=scanBluetoothCommunicationDevices();
            if(comm.isEmpty()){ sendStatus(false,0,"● LIMITE: Android não expôs nenhuma rota Bluetooth de comunicação."); return; }
            if(comm.size()==1){
                sendStatus(false,1,"● LIMITE confirmado: só 1 rota Bluetooth HFP/SCO exposta. Não há segunda rota para alternar.");
                // Ainda testa a única rota para provar se o microfone vem do headset.
                testOneCommunicationRoute(comm.get(0),gain);
                return;
            }
            sendStatus(true,comm.size(),"● TESTE LIMITE: alternando rota A/B a cada 2,5 s. A mídia dupla pode cair durante a troca.");
            updateNotification("Teste limite A/B"); running.set(true);
            int idx=0;
            while(running.get()){
                AudioDeviceInfo d=comm.get(idx%comm.size());
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                boolean ok=audioManager.setCommunicationDevice(d);
                sendStatus(ok,comm.size(),"● LIMITE A/B: rota "+(idx%comm.size()+1)+"/"+comm.size()+" → "+d.getProductName()+" • "+(ok?"ativa":"falhou"));
                Thread.sleep(2500);
                idx++;
            }
        }catch(Throwable t){ sendStatus(false,-1,"● Erro teste limite: "+t.getClass().getSimpleName()); }
        finally{ try{ if(Build.VERSION.SDK_INT>=31)audioManager.clearCommunicationDevice(); }catch(Exception ignored){} try{audioManager.setMode(AudioManager.MODE_NORMAL);}catch(Exception ignored){} cleanupAudio(); }
    }

    private void testOneCommunicationRoute(AudioDeviceInfo d,int gain)throws Exception{
        if(Build.VERSION.SDK_INT<31)return;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean ok=audioManager.setCommunicationDevice(d);
        if(!ok){ sendStatus(false,1,"● A única rota Bluetooth não pôde ser selecionada."); return; }
        final int rate=16000, enc=AudioFormat.ENCODING_PCM_16BIT;
        int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,enc); int bs=Math.max(4096,min*2);
        recorderA=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,enc,bs);
        player=createMediaPlayer(rate,enc,bs);
        recorderA.startRecording(); player.play(); running.set(true);
        sendStatus(true,1,"● 1 rota HFP/SCO ativa. Fale no Y10 e veja se a voz entra; segundo mic continua indisponível.");
        short[] mono=new short[bs/2], stereo=new short[bs]; float g=Math.max(0f,gain/100f);
        while(running.get()){
            int n=recorderA.read(mono,0,mono.length); if(n<=0)continue; int p=0;
            for(int i=0;i<n;i++){ int v=(int)(mono[i]*g); v=Math.max(-32768,Math.min(32767,v)); short s=(short)v; stereo[p++]=s; stereo[p++]=s; }
            player.write(stereo,0,p,AudioTrack.WRITE_BLOCKING);
        }
    }

    private AudioTrack createMediaPlayer(int rate,int enc,int bs){
        AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFormat fmt=new AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        int outMin=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,enc);
        return new AudioTrack(attrs,fmt,Math.max(bs*2,outMin*2),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    private AudioRecord createRecorder(int rate,int enc,int bs,AudioDeviceInfo d){
        AudioRecord r=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,rate,AudioFormat.CHANNEL_IN_MONO,enc,bs);
        try{r.setPreferredDevice(d);}catch(Throwable ignored){}
        return r;
    }

    private List<AudioDeviceInfo> scanBluetoothInputs(){
        List<AudioDeviceInfo> l=new ArrayList<>();
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)){
            int t=d.getType(); if(t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||(Build.VERSION.SDK_INT>=31&&t==AudioDeviceInfo.TYPE_BLE_HEADSET))l.add(d);
        }
        return l;
    }

    private List<AudioDeviceInfo> scanBluetoothCommunicationDevices(){
        List<AudioDeviceInfo> l=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){
            try{ for(AudioDeviceInfo d:audioManager.getAvailableCommunicationDevices()){ int t=d.getType(); if(t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||t==AudioDeviceInfo.TYPE_BLE_HEADSET)l.add(d); } }catch(Throwable ignored){}
        }
        return l;
    }

    private void sendStatus(boolean isRunning,int micCount,String msg){ Intent i=new Intent(STATUS_ACTION); i.setPackage(getPackageName()); i.putExtra("running",isRunning); i.putExtra("micCount",micCount); i.putExtra("message",msg); sendBroadcast(i); }
    private void updateNotification(String text){ ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,buildNotification(text)); }
    private Notification buildNotification(String text){ PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT); Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this); return b.setContentTitle("Y10 Intercom Bridge V4").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_headset).setOngoing(true).setContentIntent(pi).build(); }

    private void cleanupAudio(){
        running.set(false);
        try{if(recorderA!=null)recorderA.stop();}catch(Exception ignored){} try{if(recorderB!=null)recorderB.stop();}catch(Exception ignored){} try{if(player!=null)player.stop();}catch(Exception ignored){}
        try{if(recorderA!=null)recorderA.release();}catch(Exception ignored){} try{if(recorderB!=null)recorderB.release();}catch(Exception ignored){} try{if(player!=null)player.release();}catch(Exception ignored){}
        recorderA=null; recorderB=null; player=null;
    }
    @Override public void onDestroy(){ cleanupAudio(); try{if(Build.VERSION.SDK_INT>=31)audioManager.clearCommunicationDevice();}catch(Exception ignored){} try{audioManager.setMode(AudioManager.MODE_NORMAL);}catch(Exception ignored){} try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){} super.onDestroy(); }
    @Override public IBinder onBind(Intent intent){return null;}
                                                                                                    }

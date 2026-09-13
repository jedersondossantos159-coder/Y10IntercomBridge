package com.example.y10bridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String STATUS_ACTION = "com.example.y10bridge.STATUS";
    private static final int CYAN = Color.rgb(0, 184, 255);
    private static final int GREEN = Color.rgb(0, 220, 120);
    private static final int RED = Color.rgb(255, 76, 96);
    private static final int ORANGE = Color.rgb(255, 170, 0);
    private static final int BG = Color.rgb(4, 10, 18);
    private static final int CARD = Color.rgb(8, 23, 38);

    private AudioManager audioManager;
    private BluetoothAdapter bluetoothAdapter;
    private SeekBar gainBar;
    private TextView gainValue;
    private TextView micAStatus;
    private TextView micBStatus;
    private TextView outputStatus;
    private TextView mainStatus;
    private TextView diagnostic;
    private TextView modeStatus;

    private final List<AudioDeviceInfo> bluetoothInputs = new ArrayList<>();
    private final List<AudioDeviceInfo> bluetoothOutputs = new ArrayList<>();
    private int a2dpConnected = -1;
    private int hfpConnected = -1;
    private boolean experimentalMode = false;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            int micCount = intent.getIntExtra("micCount", -1);
            boolean running = intent.getBooleanExtra("running", false);
            if (message != null) {
                mainStatus.setText(message);
                mainStatus.setTextColor(running ? GREEN : (message.contains("LIMITE") ? ORANGE : RED));
            }
            if (micCount >= 0) updateMicCards(micCount);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        setContentView(buildUi());
        requestPermissionsIfNeeded();
        refreshDevices();
        queryBluetoothProfiles();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
    }

    @Override protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshDevices();
        queryBluetoothProfiles();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        ImageView hero = new ImageView(this);
        hero.setImageResource(R.drawable.y10_header);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.setAdjustViewBounds(true);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, dp(190));
        heroParams.bottomMargin = dp(14);
        hero.setLayoutParams(heroParams);
        root.addView(hero);

        TextView title = text("Y10 INTERCOM BRIDGE V4", 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView subtitle = text("modo limite • 2 Y10 • 1 Redmi", 15, CYAN, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, dp(16));
        root.addView(subtitle);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        micAStatus = deviceCard("🎙 Y10 A", "verificando...");
        micBStatus = deviceCard("🎙 Y10 B", "verificando...");
        row.addView(wrapWeight(micAStatus));
        row.addView(wrapWeight(micBStatus));
        root.addView(row);

        outputStatus = cardText("🎧 SAÍDA: verificando...", 16, Color.WHITE);
        root.addView(outputStatus);

        Button connect = actionButton("CONECTAR / PREPARAR OS 2 Y10", Color.rgb(15, 90, 145));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(58));
        cp.topMargin = dp(12);
        root.addView(connect, cp);

        modeStatus = cardText("MODO: Dual Media preservado", 14, GREEN);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(10);
        root.addView(modeStatus, mp);

        Button mode = actionButton("ATIVAR TESTE LIMITE A/B", Color.rgb(105, 61, 0));
        LinearLayout.LayoutParams modeP = new LinearLayout.LayoutParams(-1, dp(54));
        modeP.topMargin = dp(10);
        root.addView(mode, modeP);

        TextView gainTitle = text("GANHO DO MICROFONE", 15, Color.LTGRAY, true);
        gainTitle.setPadding(dp(4), dp(18), 0, dp(4));
        root.addView(gainTitle);
        LinearLayout gainRow = new LinearLayout(this);
        gainRow.setOrientation(LinearLayout.HORIZONTAL);
        gainRow.setGravity(Gravity.CENTER_VERTICAL);
        gainBar = new SeekBar(this);
        gainBar.setMax(300);
        gainBar.setProgress(100);
        gainValue = text("100%", 15, CYAN, true);
        gainRow.addView(gainBar, new LinearLayout.LayoutParams(0, dp(48), 1f));
        gainRow.addView(gainValue, new LinearLayout.LayoutParams(dp(66), dp(48)));
        root.addView(gainRow);
        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { gainValue.setText(p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        Button start = actionButton("INICIAR INTERCOM", CYAN);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(64));
        sp.topMargin = dp(18);
        root.addView(start, sp);
        Button stop = actionButton("PARAR", Color.rgb(29, 58, 83));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, dp(56));
        stp.topMargin = dp(10);
        root.addView(stop, stp);

        mainStatus = cardText("● Pronto", 15, Color.LTGRAY);
        LinearLayout.LayoutParams statusP = new LinearLayout.LayoutParams(-1, -2);
        statusP.topMargin = dp(16);
        root.addView(mainStatus, statusP);
        diagnostic = text("", 12, Color.rgb(120, 150, 175), false);
        diagnostic.setPadding(dp(8), dp(10), dp(8), 0);
        root.addView(diagnostic);

        connect.setOnClickListener(v -> openBluetoothSetup());
        mode.setOnClickListener(v -> {
            experimentalMode = !experimentalMode;
            if (experimentalMode) {
                mode.setText("DESATIVAR TESTE LIMITE A/B");
                modeStatus.setText("MODO: LIMITE A/B — tenta alternar rota de comunicação entre Y10");
                modeStatus.setTextColor(ORANGE);
            } else {
                mode.setText("ATIVAR TESTE LIMITE A/B");
                modeStatus.setText("MODO: Dual Media preservado");
                modeStatus.setTextColor(GREEN);
            }
        });
        start.setOnClickListener(v -> startBridge());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            mainStatus.setText("● Intercom parado");
            mainStatus.setTextColor(Color.LTGRAY);
        });
        return scroll;
    }

    private void openBluetoothSetup() {
        Toast.makeText(this, "Na tela Bluetooth, confirme que os DOIS Y10 estão conectados para áudio de mídia e chamadas. Depois volte ao app.", Toast.LENGTH_LONG).show();
        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void refreshDevices() {
        bluetoothInputs.clear(); bluetoothOutputs.clear();
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) bluetoothInputs.add(d);
        }
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER))) bluetoothOutputs.add(d);
        }
        updateMicCards(bluetoothInputs.size());
        outputStatus.setText(bluetoothOutputs.size() >= 2 ? "🎧 SAÍDA: 2 dispositivos Bluetooth • mídia dupla disponível" : "🎧 SAÍDA: " + bluetoothOutputs.size() + " dispositivo(s) Bluetooth");
        outputStatus.setTextColor(bluetoothOutputs.size() >= 2 ? GREEN : Color.LTGRAY);
        renderDiagnostic();
    }

    private void updateMicCards(int count) {
        if (count >= 2) {
            micAStatus.setText("🎙 Y10 A\n● MIC EXPOSTO"); micAStatus.setTextColor(GREEN);
            micBStatus.setText("🎙 Y10 B\n● MIC EXPOSTO"); micBStatus.setTextColor(GREEN);
        } else if (count == 1) {
            micAStatus.setText("🎙 ROTA BT\n● 1 MIC EXPOSTO"); micAStatus.setTextColor(ORANGE);
            micBStatus.setText("🎙 SEGUNDO MIC\n● NÃO EXPOSTO"); micBStatus.setTextColor(RED);
        } else {
            micAStatus.setText("🎙 Y10 A\n● MIC NÃO EXPOSTO"); micAStatus.setTextColor(RED);
            micBStatus.setText("🎙 Y10 B\n● MIC NÃO EXPOSTO"); micBStatus.setTextColor(RED);
        }
    }

    private void queryBluetoothProfiles() {
        if (bluetoothAdapter == null || (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return;
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    if (profile == BluetoothProfile.A2DP) a2dpConnected = ((BluetoothA2dp) proxy).getConnectedDevices().size();
                    else if (profile == BluetoothProfile.HEADSET) hfpConnected = ((BluetoothHeadset) proxy).getConnectedDevices().size();
                } catch (Exception ignored) {}
                renderDiagnostic();
                try { bluetoothAdapter.closeProfileProxy(profile, proxy); } catch (Exception ignored) {}
            }
            @Override public void onServiceDisconnected(int profile) {}
        };
        try { bluetoothAdapter.getProfileProxy(this, listener, BluetoothProfile.A2DP); } catch (Exception ignored) {}
        try { bluetoothAdapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET); } catch (Exception ignored) {}
    }

    private void renderDiagnostic() {
        if (diagnostic == null) return;
        StringBuilder s = new StringBuilder();
        s.append("DIAGNÓSTICO V4\n");
        s.append("Entradas BT expostas ao AudioRecord: ").append(bluetoothInputs.size()).append("\n");
        for (int i=0;i<bluetoothInputs.size();i++) s.append("MIC ").append(i+1).append(": ").append(name(bluetoothInputs.get(i))).append(" type=").append(bluetoothInputs.get(i).getType()).append("\n");
        s.append("Saídas BT: ").append(bluetoothOutputs.size()).append("\n");
        for (int i=0;i<bluetoothOutputs.size();i++) s.append("OUT ").append(i+1).append(": ").append(name(bluetoothOutputs.get(i))).append(" type=").append(bluetoothOutputs.get(i).getType()).append("\n");
        s.append("A2DP conectados: ").append(a2dpConnected < 0 ? "verificando" : a2dpConnected).append("\n");
        s.append("HFP/HEADSET conectados: ").append(hfpConnected < 0 ? "verificando" : hfpConnected).append("\n");
        s.append("Obs.: Android pode conectar 2 em A2DP, mas expor só 1 rota HFP/SCO de microfone.");
        diagnostic.setText(s.toString());
    }

    private void startBridge() {
        if (requestPermissionsIfNeeded()) return;
        refreshDevices(); queryBluetoothProfiles();
        Intent i = new Intent(this, BridgeService.class);
        i.putExtra("gain", gainBar.getProgress());
        i.putExtra("experimentalAB", experimentalMode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        mainStatus.setText(experimentalMode ? "● TESTE LIMITE A/B iniciado..." : "● Tentando intercom com mídia dupla...");
        mainStatus.setTextColor(experimentalMode ? ORANGE : CYAN);
    }

    private boolean requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return false;
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) { requestPermissions(p.toArray(new String[0]), 100); return true; }
        return false;
    }

    private TextView deviceCard(String title, String sub) { TextView v = cardText(title + "\n● " + sub, 15, Color.LTGRAY); v.setGravity(Gravity.CENTER); return v; }
    private LinearLayout wrapWeight(View child) { LinearLayout b = new LinearLayout(this); b.setPadding(dp(4),0,dp(4),0); b.addView(child,new LinearLayout.LayoutParams(-1,dp(92))); b.setLayoutParams(new LinearLayout.LayoutParams(0,dp(92),1f)); return b; }
    private TextView cardText(String value,int size,int color){ TextView v=text(value,size,color,false); v.setPadding(dp(16),dp(15),dp(16),dp(15)); GradientDrawable g=new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(18)); g.setStroke(dp(1),Color.rgb(12,86,140)); v.setBackground(g); return v; }
    private Button actionButton(String label,int color){ Button b=new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(17); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD)); b.setAllCaps(false); GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(24)); g.setStroke(dp(1),CYAN); b.setBackground(g); return b; }
    private TextView text(String value,int size,int color,boolean bold){ TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL)); return v; }
    private String name(AudioDeviceInfo d){ return d.getProductName()+" [ID "+d.getId()+"]"; }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
}

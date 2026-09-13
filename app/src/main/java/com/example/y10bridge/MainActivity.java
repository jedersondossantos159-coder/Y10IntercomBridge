package com.example.y10bridge;

import android.Manifest;
import android.app.Activity;
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
import java.util.List;

public class MainActivity extends Activity {

    private static final String STATUS_ACTION = "com.example.y10bridge.STATUS";
    private static final int CYAN = Color.rgb(0, 184, 255);
    private static final int GREEN = Color.rgb(0, 220, 120);
    private static final int RED = Color.rgb(255, 76, 96);
    private static final int BG = Color.rgb(4, 10, 18);
    private static final int CARD = Color.rgb(8, 23, 38);

    private AudioManager audioManager;
    private SeekBar gainBar;
    private TextView gainValue;
    private TextView micAStatus;
    private TextView micBStatus;
    private TextView outputStatus;
    private TextView mainStatus;
    private TextView diagnostic;

    private final List<AudioDeviceInfo> bluetoothInputs = new ArrayList<>();
    private final List<AudioDeviceInfo> bluetoothOutputs = new ArrayList<>();

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            int micCount = intent.getIntExtra("micCount", -1);
            boolean running = intent.getBooleanExtra("running", false);

            if (message != null) {
                mainStatus.setText(message);
                mainStatus.setTextColor(running ? GREEN : RED);
            }

            if (micCount >= 0) {
                updateMicCards(micCount);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setContentView(buildUi());
        requestPermissionsIfNeeded();
        refreshDevices();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
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
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190));
        heroParams.bottomMargin = dp(14);
        hero.setLayoutParams(heroParams);
        root.addView(hero);

        TextView title = text("Y10 INTERCOM BRIDGE", 28, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = text("2 microfones • 2 saídas • 1 celular", 15, CYAN, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, dp(18));
        root.addView(subtitle);

        LinearLayout devicesRow = new LinearLayout(this);
        devicesRow.setOrientation(LinearLayout.HORIZONTAL);
        devicesRow.setWeightSum(2f);

        micAStatus = deviceCard("🎙  Y10 A", "procurando microfone...");
        micBStatus = deviceCard("🎙  Y10 B", "procurando microfone...");
        devicesRow.addView(wrapWeight(micAStatus));
        devicesRow.addView(wrapWeight(micBStatus));
        root.addView(devicesRow);

        outputStatus = cardText("🎧  SAÍDA: verificando os dois Y10...", 16, Color.WHITE);
        root.addView(outputStatus);

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
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gainValue.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Button start = actionButton("INICIAR INTERCOM", CYAN);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        startParams.topMargin = dp(18);
        root.addView(start, startParams);

        Button stop = actionButton("PARAR", Color.rgb(29, 58, 83));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        stopParams.topMargin = dp(10);
        root.addView(stop, stopParams);

        mainStatus = cardText("● Pronto para verificar os 2 microfones Y10", 15, Color.LTGRAY);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        root.addView(mainStatus, statusParams);

        diagnostic = text("", 12, Color.rgb(120, 150, 175), false);
        diagnostic.setPadding(dp(8), dp(10), dp(8), 0);
        root.addView(diagnostic);

        start.setOnClickListener(v -> startBridge());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            mainStatus.setText("● Intercom parado");
            mainStatus.setTextColor(Color.LTGRAY);
        });

        return scroll;
    }

    private void refreshDevices() {
        bluetoothInputs.clear();
        bluetoothOutputs.clear();

        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                bluetoothInputs.add(d);
            }
        }

        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 &&
                            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))) {
                bluetoothOutputs.add(d);
            }
        }

        updateMicCards(bluetoothInputs.size());
        outputStatus.setText(bluetoothOutputs.size() >= 2
                ? "🎧  SAÍDA: 2 dispositivos Bluetooth detectados • mídia automática"
                : "🎧  SAÍDA: " + bluetoothOutputs.size() + " dispositivo(s) Bluetooth detectado(s)");
        outputStatus.setTextColor(bluetoothOutputs.size() >= 2 ? GREEN : Color.LTGRAY);

        StringBuilder s = new StringBuilder();
        s.append("DIAGNÓSTICO\n");
        s.append("Entradas Bluetooth reais: ").append(bluetoothInputs.size()).append("\n");
        for (int i = 0; i < bluetoothInputs.size(); i++) {
            s.append("MIC ").append(i + 1).append(": ").append(name(bluetoothInputs.get(i))).append("\n");
        }
        s.append("Saídas Bluetooth: ").append(bluetoothOutputs.size()).append("\n");
        for (int i = 0; i < bluetoothOutputs.size(); i++) {
            s.append("OUT ").append(i + 1).append(": ").append(name(bluetoothOutputs.get(i))).append("\n");
        }
        diagnostic.setText(s.toString());
    }

    private void updateMicCards(int count) {
        if (count >= 2) {
            micAStatus.setText("🎙  Y10 A\n● MICROFONE ATIVO");
            micAStatus.setTextColor(GREEN);
            micBStatus.setText("🎙  Y10 B\n● MICROFONE ATIVO");
            micBStatus.setTextColor(GREEN);
        } else if (count == 1) {
            micAStatus.setText("🎙  Y10 A\n● 1 MIC DETECTADO");
            micAStatus.setTextColor(GREEN);
            micBStatus.setText("🎙  Y10 B\n● MIC NÃO EXPOSTO");
            micBStatus.setTextColor(RED);
        } else {
            micAStatus.setText("🎙  Y10 A\n● MIC NÃO EXPOSTO");
            micAStatus.setTextColor(RED);
            micBStatus.setText("🎙  Y10 B\n● MIC NÃO EXPOSTO");
            micBStatus.setTextColor(RED);
        }
    }

    private void startBridge() {
        if (requestPermissionsIfNeeded()) return;
        refreshDevices();

        if (bluetoothInputs.size() < 2) {
            Toast.makeText(this,
                    "O Android não está expondo os 2 microfones Y10 ao app. O serviço vai tentar novamente sem usar o microfone do celular.",
                    Toast.LENGTH_LONG).show();
        }

        Intent intent = new Intent(this, BridgeService.class);
        intent.putExtra("gain", gainBar.getProgress());
        intent.putExtra("strictBluetoothMics", true);

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);

        mainStatus.setText("● Tentando abrir os 2 microfones Y10...");
        mainStatus.setTextColor(CYAN);
    }

    private boolean requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return false;
        List<String> permissions = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 100);
            return true;
        }
        return false;
    }

    private TextView deviceCard(String title, String sub) {
        TextView v = cardText(title + "\n● " + sub, 15, Color.LTGRAY);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private LinearLayout wrapWeight(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(4), 0, dp(4), 0);
        box.addView(child, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92)));
        box.setLayoutParams(new LinearLayout.LayoutParams(0, dp(92), 1f));
        return box;
    }

    private TextView cardText(String value, int size, int color) {
        TextView v = text(value, size, color, false);
        v.setPadding(dp(16), dp(15), dp(16), dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(12, 86, 140));
        v.setBackground(bg);
        return v;
    }

    private Button actionButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), CYAN);
        b.setBackground(bg);
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }

    private String name(AudioDeviceInfo d) {
        return d.getProductName() + " [ID " + d.getId() + "]";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

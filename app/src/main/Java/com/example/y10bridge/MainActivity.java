package com.example.y10bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Y10 Intercom Bridge");
        title.setTextSize(28);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(this);
        info.setText("\nExperimental: tenta usar o microfone de um headset Bluetooth e reproduzir o áudio em tempo real.\n\n" +
                "Antes de iniciar, conecte os dois Y10 ao Redmi e deixe ambos habilitados para áudio.");
        info.setTextSize(16);
        root.addView(info);

        Button start = new Button(this);
        start.setText("INICIAR INTERCOM");
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("PARAR");
        root.addView(stop);

        status = new TextView(this);
        status.setText("\nStatus: parado");
        status.setTextSize(16);
        root.addView(status);

        start.setOnClickListener(v -> {
            if (requestNeededPermissions()) return;

            Intent i = new Intent(this, BridgeService.class);

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }

            status.setText("\nStatus: intercom iniciado");
        });

        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            status.setText("\nStatus: parado");
        });

        setContentView(root);
    }

    private boolean requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }

        List<String> needed = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 100);
            return true;
        }

        return false;
    }
}

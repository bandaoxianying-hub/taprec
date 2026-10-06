package com.taprec;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView info;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        info = new TextView(this);
        info.setTextSize(16);
        root.addView(info);

        Button b1 = new Button(this);
        b1.setText("1. 打开无障碍设置，开启 TapRec");
        b1.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(b1);

        Button b2 = new Button(this);
        b2.setText("2. 显示悬浮面板");
        b2.setOnClickListener(v -> {
            if (RecService.instance == null) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            } else {
                RecService.instance.showPanel();
            }
        });
        root.addView(b2);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = RecService.instance != null;
        String head = on ? "服务状态：已开启\n\n" : "服务状态：未开启\n\n";
        info.setText(head
                + "用法：\n"
                + "1. 开启无障碍服务后，屏幕右侧会出现悬浮面板。\n"
                + "2. 点“录制”，然后正常操作平板，点“停止”结束并保存。\n"
                + "3. 回到起始画面，点“回放”，3 秒后自动重复刚才的操作。\n"
                + "4. “次数”可切换回放次数。\n\n"
                + "提示：录制时每个动作要等手指抬起后才生效；两个动作之间请停顿 1 秒左右。\n\n"
                + "如果无障碍开关是灰色的：设置 → 应用 → TapRec → 右上角菜单 → 允许受限制的设置。\n\n");
    }
}

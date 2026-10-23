package com.jyjl.aivideo;

import android.os.Bundle;
import android.webkit.ValueCallback;
import com.getcapacitor.BridgeActivity;

/** 安卓壳：返回键优先交给页面；注册目录/下载插件。 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AIVDirPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onBackPressed() {
        try {
            getBridge().getWebView().evaluateJavascript(
                "(function(){try{return (window.__appHandleBack&&window.__appHandleBack())?'1':'0'}catch(e){return '0'}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        boolean handled = value != null && value.contains("1");
                        if (!handled) {
                            MainActivity.super.onBackPressed();
                        }
                    }
                }
            );
        } catch (Exception e) {
            super.onBackPressed();
        }
    }
}

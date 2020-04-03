package com.buyfull.sdkdemo;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.buyfull.sdk.BuyfullSDK;
import com.buyfull.sdkdemo.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;


//APP要自已申请麦克风权限，申请成功后才能正常调用SDK
//此DEMO中自带麦请麦克风权限代码，可以自行修改
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BuyfullSDKDemo";
    private static final int REQUEST_PHONE_PERMISSION = 100;
    private static final int REQUEST_RECORD_PERMISSION = 200;

    private TextView resultText;
    private String lastReqID;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkInit(); //进入程序申请READPHONE权限，实际中可以自行改变

        resultText = (TextView)findViewById(R.id.textView);

        Button test = (Button)findViewById(R.id.button2);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                resultText.setText("detecting...");
                checkDetect();
            }
        });//检测权限，然后开始录音检测

        Button test2 = (Button)findViewById(R.id.button);
        test2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (lastReqID != null){
                    resultText.setText("RequestID 已经在剪切板中，可以在微信中粘贴给工作人员用做查询");
//                    BuyfullSDK.getInstance().debugUpload(lastReqID);
                    ClipboardManager mClipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData clipData = ClipData.newPlainText("copy from buyfull sdk", lastReqID);
                    mClipboardManager.setPrimaryClip(clipData);
                }else{
                    resultText.setText("please detect first");
                }
            }
        });//检测权限，然后开始录音检测
    }

    @Override
    protected void onPause() {
        BuyfullSDK.getInstance().stop();//退到后台，停止录音，再次启动检测会比较慢
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        BuyfullSDK.destory();
        super.onDestroy();
    }


    //检查权限后初始化
    protected void checkInit(){
        BuyfullSDK sdk = BuyfullSDK.getInstance();
        // appkey和sandbox请向动听员工询问，tokenURL需要自行布署，此处只是DEMO
        sdk.setSDKInfo(MyApplication.appKey,MyApplication.tokenURL, MyApplication.detectURL);
        //检测权限
        PackageManager pkgManager = getPackageManager();
        // read phone state用于获取 imei 设备信息
        boolean phoneSatePermission =
                pkgManager.checkPermission(Manifest.permission.READ_PHONE_STATE, getPackageName()) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= 23 && !phoneSatePermission) {
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.READ_PHONE_STATE},
                    REQUEST_PHONE_PERMISSION);
        } else {
            BuyfullSDK.getInstance().setContext(this);//可以重复调用，此方法不会保存context
        }
    }

    private  void doDetect(){
        BuyfullSDK sdk = BuyfullSDK.getInstance();
        if (sdk.isDetecting()){
            resultText.setText("Please retry later");
        }else{
            JSONObject options = new JSONObject();
            try{
                //此处只是DEMO, 在BuyfullSDK.java中的detectRequest中发送customData
                options.put("customData", "anything you want");
                //以下参数会对检测性能有影响
//                options.put("alwaysAutoRetry", true);//是否(一直)在检测不成功时自动重试(直到超时）
//                options.put("firstTimeBoost", true);//初次检测时，如果检测不成功，会自动重试（仅第一次）
//                options.put("stopAfterReturn", true);//是否在录音返回后自动停止录音，再次启动检测会比较慢
            }catch (Exception e){}

            BuyfullSDK.getInstance().detect(options, new BuyfullSDK.IDetectCallback() {
                @Override
                public void onDetect(final JSONObject options, final float dB,final String result,final Exception error) {
                    if (error != null){
                        String reason = error.getLocalizedMessage();
                        if (reason.equals("no_result")) {
                            //你可以自动重试检测
                            lastReqID = result;
                            resultText.setText("No detect result, signal dB is:" + dB);
                        } else if (reason.equals("token_error")) {
                            //你可以自动重试检测
                            resultText.setText("Please fetch token again and retry");
                        } else if (reason.equals("record_fail")){
                            //你可以自动重试检测
                                resultText.setText("Please check recorder and retry");
                        } else{
                            //你可以记录下错误再自动重试检测
                            error.printStackTrace();
                            resultText.setText(error.getLocalizedMessage());
                        }
                    }else if(result == null || result.equals("")){
                        //你可以自动重试检测
                        resultText.setText("No detect result, signal dB is:" + dB);
                    }else {
                        //有检测结果，你可以处理后自动重试检测
                        Log.d(TAG,result);
                        resultText.setText("got result, signal dB is:" + dB);

                        try {
                            JSONObject jsonObj = (JSONObject) new JSONTokener(result).nextValue();
                            lastReqID = jsonObj.getString("record_id");
                            String msg = jsonObj.getString("msg");
                            resultText.setText(result);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        }
    }

    //检查权限后开始检测
    private void checkDetect(){
        PackageManager pkgManager = getPackageManager();

        boolean hasRecordPermission =
                pkgManager.checkPermission(Manifest.permission.RECORD_AUDIO, getPackageName()) == PackageManager.PERMISSION_GRANTED;

        if (!hasRecordPermission) {
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_PERMISSION);
        } else {
            doDetect();
        }
    }
    //请求权限的回调
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        //获得权限后请调用一下setContext
        if (requestCode == REQUEST_PHONE_PERMISSION) {
            if ((grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                BuyfullSDK.getInstance().setContext(this);//可以重复调用，此方法不会保存context
            } else {
                Log.e(TAG, "We highly recommend that you need to grant the special permissions before initializing the SDK, otherwise some "
                        + "functions will not work");
                BuyfullSDK.getInstance().setContext(this);//可以重复调用，此方法不会保存context
            }
        }else if (requestCode == REQUEST_RECORD_PERMISSION) {
            if ((grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                BuyfullSDK.getInstance().setContext(this);//可以重复调用，此方法不会保存context
                checkDetect();
            } else {
                Log.e(TAG,"please goto setting and grant record permission");
                //请自行打开权限设置页面
                PermissionPageUtils utils = new PermissionPageUtils(this);
                utils.jumpPermissionPage();
            }
        }else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

}

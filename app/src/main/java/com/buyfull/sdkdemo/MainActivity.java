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
    protected void onDestroy() {
        super.onDestroy();
    }
    //检查权限后初始化
    protected void checkInit(){
        BuyfullSDK sdk = BuyfullSDK.getInstance();
        // appkey和sandbox请向动听员工询问，tokenURL需要自行布署，此处只是DEMO
        sdk.setSDKInfo(MyApplication.appKey,MyApplication.isSandbox,MyApplication.tokenURL);
        // userID或phoneNumber可以做为数据分析标识通过动听后台API返回，请任意设置一个
        sdk.setUserID("13xxxxxxxxx","custom user id");

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
        sdk.setUserID("13xxxxxxxxx","custom user id");
        if (sdk.isDetecting()){
            resultText.setText("Please retry later");
        }else{
            JSONObject options = new JSONObject();
            try{
                options.put("firstTimeBoost", true);
//                options.put("stopAfterReturn", true);
            }catch (Exception e){}

            BuyfullSDK.getInstance().detect(options, new BuyfullSDK.IDetectCallback() {
                @Override
                public void onDetect(final float dB,final String json,final Exception error) {
                    if (error != null){
                        error.printStackTrace();
                        resultText.setText(error.getLocalizedMessage());
                    }else if(json == null){
                        resultText.setText("No detect result, signal dB is:" + dB);
                    }else {
                        Log.d(TAG,json);
                        resultText.setText("got result, signal dB is:" + dB);

                        try {
                            JSONObject jsonObj = (JSONObject) new JSONTokener(json).nextValue();
                            lastReqID = jsonObj.getString("reqid");
                            int tagCount = jsonObj.getInt("count");
                            if (tagCount > 0){
                                JSONArray allTags = jsonObj.getJSONArray("allTags");
                                resultText.setText("RequestID is:" + lastReqID + "\nTest result is:" + allTags.join(","));
                            }else{
                                JSONArray sortedResults = jsonObj.getJSONArray("sortByPowerResult");
                                JSONObject result1 = sortedResults.getJSONObject(0);
                                JSONObject result2 = sortedResults.getJSONObject(1);
                                resultText.setText("RequestID is:" + lastReqID + "\nTest result is null, power is (dB):" + result1.getDouble("power") + " | " + result2.getDouble("power"));
                            }
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

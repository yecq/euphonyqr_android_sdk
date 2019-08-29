package com.buyfull.sdkdemo;

import android.Manifest;
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

//APP要自已申请麦克风权限，申请成功后才能正常调用SDK
//此DEMO中自带麦请麦克风权限代码，可以自行修改
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BuyfullSDKDemo";
    private static final int REQUEST_PHONE_PERMISSION = 100;
    private static final int REQUEST_RECORD_PERMISSION = 200;

    private TextView resultText;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkInit(); //进入程序申请READPHONE权限，实际中可以自行改变
        resultText = (TextView)findViewById(R.id.textView);
        Button test = (Button)findViewById(R.id.button);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                checkDetect(); 
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //test destory
        BuyfullSDK.destoryInstance();
    }
    //检查权限后初始化
    protected void checkInit(){
        PackageManager pkgManager = getPackageManager();
        // read phone state用于获取 imei 设备信息
        boolean phoneSatePermission =
                pkgManager.checkPermission(Manifest.permission.READ_PHONE_STATE, getPackageName()) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= 23 && !phoneSatePermission) {
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.READ_PHONE_STATE},
                    REQUEST_PHONE_PERMISSION);
        } else {
            BuyfullSDK.getInstance().setContext(this);
        }
    }

    private  void doDetect(){
        BuyfullSDK sdk = BuyfullSDK.getInstance();
        sdk.setUserID("13xxxxxxxxx","custom user id");
        if (sdk.isDetecting()){
            resultText.setText("Please retry later");
        }else{
            BuyfullSDK.getInstance().detect("custom data", new BuyfullSDK.IDetectCallback() {
                @Override
                public void onDetect(final float dB,final String json,final Exception error) {
                    //回调不在主线程
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (error != null){
                                resultText.setText(error.getLocalizedMessage());
                            }else if(json == null){
                                resultText.setText("No detect result, signal dB is:" + dB);
                            }else {

                            }
                        }
                    });
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
    //请求权限
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        //获得权限后请调用一下setContext
        if (requestCode == REQUEST_PHONE_PERMISSION) {
            if ((grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                BuyfullSDK.getInstance().setContext(this);
            } else {
                Log.e(TAG, "We highly recommend that you need to grant the special permissions before initializing the SDK, otherwise some "
                        + "functions will not work");
                BuyfullSDK.getInstance().setContext(this);
            }
        }else if (requestCode == REQUEST_RECORD_PERMISSION) {
            if ((grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                BuyfullSDK.getInstance().setContext(this);
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

package com.buyfull.sdkdemo;

import android.app.Application;

import com.buyfull.sdk.BuyfullSDK;

public class MyApplication extends Application {
    public static final String appKey = "75ba120532f44aa7a8cd431a2c2a50ef";
    public static final String tokenURL = "https://sandbox.euphonyqr.com/testycq2/buyfulltoken";
    public static final boolean isSandbox = true;
    @Override
    public void onCreate() {
        super.onCreate();
        //建议在app中初始化实例和参数
        BuyfullSDK sdk = BuyfullSDK.getInstance();
        sdk.setContext(getApplicationContext());
        // appkey和sandbox请向动听员工询问，tokenURL需要自行布署，此处只是DEMO
        sdk.setSDKInfo(appKey,isSandbox,tokenURL);
        // userID或phoneNumber可以做为数据分析标识通过动听后台API返回，请任意设置一个
        sdk.setUserID("13xxxxxxxxx","custom user id");
    }

}

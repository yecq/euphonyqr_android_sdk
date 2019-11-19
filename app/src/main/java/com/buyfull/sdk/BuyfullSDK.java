package com.buyfull.sdk;

import android.content.Context;

import static com.buyfull.sdk.BuyfullRecorder.LIMIT_DB;

public class BuyfullSDK {
    public interface IDetectCallback {
        /**
         * 检测完成后回调
         * @param dB        此次录音的分贝数，如果小于LIMIT_DB则不会上传检测
         * @param json      检测成功返回JSON数据，可能为空
         * @param error     如果有错误则不为空
         */
        void onDetect(final float dB, final String json, final Exception error);
    }

    public synchronized static BuyfullSDK getInstance(){
        if (instance == null){
            instance = new BuyfullSDK();
            instance.init();
        }
        return instance;
    }

    private void init() {

    }

    private volatile static BuyfullSDK      instance;

    public boolean isDetecting() {
        return BuyfullRecorder.getInstance().isRecording();
    }

    public void setContext(Context ctx){

    }

    public void detect(String customData, final IDetectCallback callback){
        if (callback == null)   return;
        if (isDetecting()){
            callback.onDetect(LIMIT_DB,null, new Exception("don't call detect while detecting"));
            return;
        }
        BuyfullRecorder.getInstance().record(null, new BuyfullRecorder.IRecordCallback() {
            @Override
            public void onRecord(float dB, byte[] pcm, BuyfullRecorder.RecordException error) {
                if (error != null){
                    callback.onDetect(dB,null,error);
                }else{
                    callback.onDetect(dB, "{}", null);
                }
            }
        });
    }

}

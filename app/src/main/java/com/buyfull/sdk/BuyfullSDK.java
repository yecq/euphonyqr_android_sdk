package com.buyfull.sdk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BuyfullSDK {
    private static final  String TAG = "BUYFULLSDK";
    private static final  int   RECORD_SAMPLE_RATE = 44100; //默认录音采样率
    private static final  float RECORD_PERIOD = 1.2f; //录音时长
    private static final  float LIMIT_DB = -120f; //分贝阈值，低于此值不上传判断

    public interface IDetectCallback {
        /**
         * 检测完成后回调
         * @param dB        此次录音的分贝数，如果小于LIMIT_DB则不会上传检测
         * @param json      检测成功返回JSON数据，可能为空
         * @param error     如果有错误则不为空
         */
        void onDetect(final float dB,final String json,final Exception error);
    }

    public interface IRecordCallback {
        /**
         * 录音结束后回调
         * @param dB        此次录音的分贝数
         * @param pcm       纯PCM数据
         * @param error     如果有错误则不为空
         */
        void onRecord(final float dB,final byte[] pcm,final Exception error);
    }

    public synchronized static BuyfullSDK getInstance(){
        if (instance == null){
            instance = new BuyfullSDK();
            instance.init();
        }
        return instance;
    }

    public synchronized static void destoryInstance(){
        if (instance != null){
            instance.destory();
            instance = null;
        }
    }

    /**
     * 请在完成了READ_PHONE_STATE权限申请后执行
     * @param ctx
     */
    public void setContext(Context ctx){
        Message msg = _notifyThread.mHandler.obtainMessage(INIT_MSG, ctx);
        msg.sendToTarget();
    }

    /**
     * appkey和sandbox请向动听员工询问，tokenURL需要自行布署
     * @param appKey
     * @param isSandbox
     * @param tokenURL
     */
    public void setSDKInfo(String appKey, boolean isSandbox, String tokenURL){
        Message msg = _notifyThread.mHandler.obtainMessage(SET_SDKINFO, new String[]{appKey,isSandbox?"1":"0", tokenURL});
        msg.sendToTarget();
    }

    /**
     * userID或phoneNumber可以做为数据分析标识通过动听后台API返回，请任意设置一个
     * @param phone
     * @param userID
     */
    public void setUserID(final String phone, final String userID){
        Message msg = _notifyThread.mHandler.obtainMessage(SET_USER_ID, new String[]{phone, userID});
        msg.sendToTarget();
    }

    /**
     * 当前是否正在录音检测，不能重复调用detect
     * @return
     */
    public synchronized boolean isDetecting(){
        return _isDetecting;
    }

    /**
     * 把下面所有的SDK方法整合执行，用户可以自行参考修改
     * 可以通过返回的requestID在动听后台查询时返回
     * @param customData    customData可以为任何字符串
     * @param callback
     */
    public void detect(String customData, IDetectCallback callback){
        if (callback == null)   return;
        if (isDetecting()){
            callback.onDetect(LIMIT_DB,null, new Exception("don't call detect while detecting"));
            return;
        }
        Message msg = _notifyThread.mHandler.obtainMessage(DETECT, new Object[]{callback,customData});
        msg.sendToTarget();
    }
    /**
     * 检测步骤的具体逻辑，可以参考修改
     *  1.检测麦克风权限
     *  2.向tokenURL(业务服务器)请求TOKEN，tokenURL(业务服务器)需要自行布署
     *  3.录音1.2秒
     *  4.判断分贝数，音量太低无效
     *  5.提取18k-20k音频，压缩
     *  6.调用API检测后返回检测结果
     *  7.对返回的JSON进行一些处理
     * @param info
     */
    private void _detect(Object[] info){
        final IDetectCallback callback = (IDetectCallback)info[0];
        final String customData = (String)info[1];

        //以免重复调用
        if (_token == null && _isInitingToken){
            _safeCallBackFail(callback, LIMIT_DB, "initing buyfull sdk, please wait", false);
            return;
        }
        if (_token != null && _isDetecting){
            _safeCallBackFail(callback, LIMIT_DB, "buyfull sdk is detecting, please wait", false);
            return;
        }

        if (!_hasMicphonePermission){
            _safeCallBackFail(callback, LIMIT_DB, "no record permission", false);
            return;
        }

        if (_deviceInfo == null){
            _safeCallBackFail(callback, LIMIT_DB, "please init context first", false);
            return;
        }

        if (_token == null && !_isInitingToken){
            _isInitingToken = true;
            try {
                String tokenResult = requestToken(_tokenURL,_appKey,_isSandbox);
                JSONObject tokenJSON = (JSONObject) new JSONTokener(tokenResult).nextValue();
                String token = tokenJSON.getString("token");
                if (token != null && !token.isEmpty()){
                    _token = token;
                }else{
                    _safeCallBackFail(callback, LIMIT_DB, "init token fail: " + tokenResult, false);
                }
            } catch (Exception e) {
                _safeCallBack(callback,LIMIT_DB,null,e, false);
            } finally {
                _isInitingToken = false;
            }
        }

        if (_token != null && !_token.isEmpty() && _hasMicphonePermission && !_isDetecting) {
            _isDetecting = true;
            record(new IRecordCallback() {
                @Override
                public void onRecord(float dB, byte[] pcm, Exception error) {
                    if (error != null){
                        _safeCallBack(callback,dB,null, error,true);
                        return;
                    }
                    if (pcm == null){
                        _safeCallBackFail(callback,dB,"record fail", true);
                        return;
                    }
                    if (dB <= LIMIT_DB){
                        Log.d(TAG, "pcm db is " + dB);
                        Log.d(TAG,"Almost no signal, return");
                        _safeCallBack(callback,dB,null, null,true);
                        return;
                    }
                    float pcmDB_start = 0;
                    try {
                        pcmDB_start = getDB(pcm, RECORD_SAMPLE_RATE, 1,16,false);
                    } catch (Exception e) {
                        _safeCallBack(callback,pcmDB_start,null, e,true);
                    }
                }
            });
        }
    }

    /**
     *  录音并且返回纯pcm数据，默认录音参数为44100,16bit,单声道，时长1.2秒。
     * @param callback
     */
    public void record(IRecordCallback callback){
        if (callback == null)   return;
        if (isDetecting()){
            callback.onRecord(LIMIT_DB,null, new Exception("don't call record while detecting"));
            return;
        }
        Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, -1, (int) (RECORD_PERIOD * 1000),callback);
        msg.sendToTarget();
    }

    /**
     *  检测18k-20k录音分贝数。
     * @param pcmData       纯PCM数据，无WAV文件头
     * @param sampleRate    44100或48000
     * @param channels      1 (单声道)或 2 (双声道交织)
     * @param bits          16 (Short)或 32 (Float)
     * @param isLastFrame   采样PCM头部还是尾部
     * @return              分贝
     * @throws Exception
     */
    public float getDB(byte[] pcmData, int sampleRate, int channels, int bits, boolean isLastFrame) throws Exception{
        if (pcmData == null){
            throw (new Exception("invalid pcmData or outBin:"));
        }
        int pcmDataSize = pcmData.length;
        int stepCount = 1024;
        int stepSize = channels * (bits / 8);
        int startIndex = 0;
        if (!(sampleRate == 44100 || sampleRate == 48000)){
            throw (new Exception("invalid sample rate:" + sampleRate));
        }else if (channels < 1 || channels > 2){
            throw (new Exception("invalid channel count:" + channels));
        }else if (!(bits == 16 || bits == 32)){
            throw (new Exception("invalid bit count:" + bits));
        }else{
            int minPCMDataSize = stepCount * stepSize;
            if (pcmDataSize < (sampleRate * stepSize)){
                throw (new Exception("invalid pcmData length:" + pcmDataSize));
            }else{
                startIndex += (pcmDataSize - minPCMDataSize);
                if (!isLastFrame){
                    startIndex -= sampleRate * stepSize;
                }
            }
        }
        ByteBuffer pcmByte = ByteBuffer.wrap(pcmData, startIndex, pcmDataSize - startIndex);

        float[] re = ((FloatBuffer)(real.asFloatBuffer().limit(stepCount))).array();
        float[] im = ((FloatBuffer)(imag.asFloatBuffer().limit(stepCount))).array();
        Arrays.fill(re,0);
        Arrays.fill(im,0);
        if (bits == 16){
            short[] pcmShort = pcmByte.asShortBuffer().array();
            for (int index = 0;index < stepCount;++index,startIndex += channels){
                re[index] = (float) (pcmShort[startIndex] / 32768.0);
            }
        }else{
            float[] pcmFloat = pcmByte.asFloatBuffer().array();
            for (int index = 0;index < stepCount;++index,startIndex += channels){
                re[index] = pcmFloat[startIndex];
            }
        }
        window_hanning(re, stepCount);
        fft(re,im,10,0);
        int s = 418, l = 45;
        if (sampleRate == 48000){
            s = 384;
            l = 42;
        }
        double db = 0;
        for (int index = 0;index < l;++index){
            double _re = re[s + index];
            double _im = im[s + index];
            db += Math.sqrt(_re * _re + _im * _im);
        }
        db /= l;
        db = Math.log(db) * (8.6858896380650365530225783783322);

        return (float)db;

    }

    /**
     * 将纯pcm采样处理，提取18k-20k音频，返回的BIN用于detect，参数指定源pcm数据格式, 录音时长一定要大于1.2秒,超出会截取最后1.2秒
     * @param pcmData       纯PCM数据，无WAV文件头
     * @param outBin        输出BIN文件
     * @param sampleRate    44100或48000
     * @param channels      1 (单声道)或 2 (双声道交织)
     * @param bits          16 (Short)或 32 (Float)
     * @return 处理后的二进制长度
     * @throws Exception
     */
    public int buildBin(byte[] pcmData, ByteBuffer outBin, int sampleRate, int channels, int bits) throws Exception{
        if (pcmData == null || outBin == null){
            throw (new Exception("invalid pcmData or outBin:"));
        }
        int pcmDataSize = pcmData.length;
        int stepCount = (int) (sampleRate * RECORD_PERIOD);
        int stepSize = channels * (bits / 8);
        int resultSize = stepCount / 8;

        if (outBin.capacity() < (resultSize + 12)){
            throw (new Exception("outBin is too small"));
        }
        int startIndex = 0;
        if (!(sampleRate == 44100 || sampleRate == 48000)){
            throw (new Exception("invalid sample rate:" + sampleRate));
        }else if (channels < 1 || channels > 2){
            throw (new Exception("invalid channel count:" + channels));
        }else if (!(bits == 16 || bits == 32)){
            throw (new Exception("invalid bit count:" + bits));
        }else{
            int minPCMDataSize = stepCount * stepSize;
            if (pcmDataSize < (sampleRate * stepSize)){
                throw (new Exception("invalid pcmData length:" + pcmDataSize));
            }else{
                startIndex += (pcmDataSize - minPCMDataSize);
            }
        }
        ByteBuffer pcmByte = ByteBuffer.wrap(pcmData, startIndex, pcmDataSize - startIndex);

        float[] re = real.asFloatBuffer().array();
        float[] im = imag.asFloatBuffer().array();
        Arrays.fill(re,0);
        Arrays.fill(im,0);
        if (bits == 16){
            short[] pcmShort = pcmByte.asShortBuffer().array();
            for (int index = 0;index < stepCount;++index,startIndex += channels){
                re[index] = (float) (pcmShort[startIndex] / 32768.0);
            }
        }else{
            float[] pcmFloat = pcmByte.asFloatBuffer().array();
            for (int index = 0;index < stepCount;++index,startIndex += channels){
                re[index] = pcmFloat[startIndex];
            }
        }

        fft(re,im,LOG2_N_WAVE,0);

        int s = 26112;
        if (sampleRate == 48000){
            s = 24064;
        }

        System.arraycopy(re,0,re,s,4096);
        System.arraycopy(im,0,im,s,4096);
        Arrays.fill(re,4096,N_WAVE,0);
        Arrays.fill(im,4096,N_WAVE,0);
        fft(re,im,13,1);

        outBin.clear();
        outBin.limit(resultSize + 12);
        if (sampleRate == 44100){
            outBin.putInt(1);
        }else{
            outBin.putInt(2);
        }
        compress(re, outBin, resultSize);

        return outBin.position();
    }

    /**
     * 请求TOKEN，有了TOKEN后才能使用BUYFULL SDK
     * @param tokenURL      需要自行布署
     * @param appkey        请在动听官网申请，并询问动听工作人员
     * @param isSandbox     如果是在sandbox.euphonyqr.com申请的appkey为true，否则为false
     * @return              token
     * @throws Exception
     */
    public String requestToken(String tokenURL, String appkey, boolean isSandbox) throws Exception{
        if (tokenURL == null || appkey == null){
            throw new Exception("Please check params");
        }
        HttpURLConnection connection = null;
        Exception error = null;
        try {
            URL url = new URL(tokenURL + "?appkey=" + appkey);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setDoOutput(false);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);

            connection.connect();
            int code = connection.getResponseCode();
            String msg = "";
            if (code == HttpURLConnection.HTTP_OK){
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    msg += line + "\n";
                }
                reader.close();
            }
            connection.disconnect();
            return msg;
        }catch (Exception e) {
            error = e;
        } finally {
            if(connection != null) {
                connection.disconnect(); //将Http连接关闭掉
            }
        }
        if (error != null)
            throw error;
        return null;
    }

    /**
     * 将参数打包后发送检测请求，返回JSON字符串
     * @param binData buildBin返回的二进制
     * @param appkey
     * @param token             tokenURL返回的token
     * @param isSandbox
     * @param deviceInfo        设备信息
     * @param phone             可为空
     * @param userID            可为空
     * @param customData        可为空
     * @return JSON结果
     * @throws Exception
     */
    public String detectRequest(byte[] binData, String appkey, String token, boolean isSandbox, String deviceInfo, String phone, String userID, String customData) throws Exception{
        if (binData == null || binData.length <= 0 || appkey == null || deviceInfo == null){
            throw new Exception("Please check params");
        }
        HttpURLConnection connection = null;
        Exception error = null;
        try {
            JSONObject params = new JSONObject();
            params.put("appkey",appkey);
            params.put("buyfulltoken",token);
            params.put("sandbox", isSandbox);
            params.put("sdkversion",SDK_VERSION);
            params.put("deviceinfo",deviceInfo);
            if (phone != null){
                params.put("phone",phone);
            }
            if (userID != null){
                params.put("userid",userID);
            }
            if (customData != null){
                params.put("customdata",customData);
            }
            String json = params.toString();
            String cmd = "soundtag-decode/decodev6/Android/BIN/" + toURLEncoded(json);
            URL url = new URL("https://api.euphonyqr.com/test/api/decode_test?cmd=" + cmd);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type","audio/mpeg");
            connection.setFixedLengthStreamingMode(binData.length);

            connection.connect();
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(binData,0,binData.length);
            outputStream.close();

            int code = connection.getResponseCode();
            String msg = "";
            if (code == 200){
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    msg += line + "\n";
                }
                reader.close();
            }
            connection.disconnect();
            return msg;
        }catch (Exception e) {
            error = e;
        } finally {
            if(connection != null) {
                connection.disconnect(); //将Http连接关闭掉
            }
        }
        if (error != null)
            throw error;
        return null;
    }

    /**
     * 处理服务器返回的原始JSON，加入一些辅助数据
     * @param rawJSON       服务器返回的原始JSON
     * @return              处理后的JSON，可能为空
     * @throws Exception
     */
    public String handleJSONResult(String rawJSON) throws Exception{
        if (rawJSON == null){
            return null;
        }
        JSONObject old_json_result = (JSONObject) new JSONTokener(rawJSON).nextValue();
        JSONArray old_result = old_json_result.getJSONArray("result");
        String requestID = old_json_result.getString("reqid");
        if (old_result == null || requestID == null || requestID.isEmpty()){
            return null;
        }

        Set<String> validTagset = new HashSet<String>();
        JSONArray allTags = new JSONArray();
        JSONArray rawResults = new JSONArray();
        JSONArray sortedResults = new JSONArray();
        JSONArray validResults = new JSONArray();

        for (int index = 0;index < old_result.length(); ++index){
            JSONObject raw_result = old_result.getJSONObject(index);
            raw_result.put("channel",index);
            rawResults.put(raw_result);
            int insertIndex = 0;
            boolean insert = false;
            if (sortedResults.length() > 0){
                double power = raw_result.getDouble("power");
                for (int index2 = 0;index2 < sortedResults.length();++index2){
                    double topPower = sortedResults.getJSONObject(index2).getDouble("power");
                    if (power > topPower){
                        insertIndex = index2;
                        insert = true;
                        break;
                    }
                }
            }
            if (!insert){
                sortedResults.put(raw_result);
            }else{
                sortedResults.put(insertIndex,raw_result);
            }
        }

        for (int index = 0; index < sortedResults.length(); ++index){
            JSONArray tags = sortedResults.getJSONObject(index).getJSONArray("tags");
            if (tags.length() > 0){
                validResults.put(sortedResults.getJSONObject(index));
                for (int index2 = 0;index2 < tags.length();++index2){
                    String tag = tags.getString(index2);
                    if (!validTagset.contains(tag)){
                        validTagset.add(tag);
                        allTags.put(tag);
                    }
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("reqid", requestID);
        result.put("rawResult", rawResults);
        result.put("sortByPowerResult", sortedResults);
        result.put("result",validResults);
        result.put("count",allTags.length());
        result.put("allTags",allTags);
        return result.toString();
    }
    ////////////////////////////////////////////////////////////////////////

    private static final float Pi = 3.14159265358979f;
    private static final int N_WAVE = (64*1024);
    private static final int LOG2_N_WAVE = (6+10);
    private static final String SDK_VERSION = "1.0.0";

    private volatile static BuyfullSDK      instance;
    private static float                    fsin[];
    private ByteBuffer                      real;
    private ByteBuffer                      imag;
    private LooperThread                    _notifyThread;
    private AudioRecord                     _recorder;
    private volatile boolean                _threadStarted;
    private ByteBuffer                      _recordBuffer;
    private ByteBuffer                      _postBuffer;
    private volatile boolean                _isDetecting;
    private volatile boolean                _isInitingToken;
    private volatile boolean _hasMicphonePermission;
    private String                          _appKey;
    private boolean                         _isSandbox;
    private String                          _tokenURL;
    private String                          _token;
    private String                          _deviceInfo;
    private String                          _userID;
    private String                          _phone;

    private static final int INIT_MSG = 1;
    private static final int DESTORY = 2;
    private static final int SET_SDKINFO = 3;
    private static final int SET_USER_ID = 4;
    private static final int DETECT = 5;
    private static final int START_RECORD = 8;
    private static final int STOP_RECORD = 9;


    private static final int START_TEST_RECORD = 15;
    private static final int STOP_TEST_RECORD = 16;

    private static class LooperThread extends Thread {
        public Handler mHandler;
        public LooperThread(String threadName)
        {
            super(threadName);
        }

        @SuppressLint("HandlerLeak")
        public void run(){
            Looper.prepare();
            mHandler = new Handler(){
                public void handleMessage(Message msg) {
                    switch (msg.what){
                        case INIT_MSG:
                            if (instance != null)
                                instance._doInitContext((Context) msg.obj);
                            break;

                        case SET_SDKINFO:
                            if (instance != null)
                                instance._set_sdk_info((String[])msg.obj);
                            break;

                        case SET_USER_ID:
                            if (instance != null)
                                instance._set_user_id((String[])msg.obj);
                            break;

                        case DETECT:
                            if (instance != null)
                                instance._detect((Object[])msg.obj);
                            break;

                        case DESTORY:
                        default:
                            Looper.myLooper().quit();
                    }
                }
            };
            instance._threadStarted = true;
            Looper.loop();
            instance._threadStarted = false;
        }
    }

    private BuyfullSDK(){
        _recordBuffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        _postBuffer = ByteBuffer.allocate(8 * 1024).order(ByteOrder.LITTLE_ENDIAN);

    }

    private void init(){
        fsin = new float[N_WAVE];
        real = ByteBuffer.allocate(N_WAVE * 4);
        imag = ByteBuffer.allocate(N_WAVE * 4);

        for (int i=0; i<N_WAVE; i++){
            fsin[i] = (float)Math.sin(2*Pi/N_WAVE*i);
        }

        _notifyThread = new LooperThread("BuyfullLoop");
        _notifyThread.start();
        while (!_threadStarted)
        {
            try{
                Thread.sleep(100);
            }
            catch (Exception e)
            {

            }
        }
    }

    private void destory(){
        _notifyThread.mHandler.obtainMessage(DESTORY).sendToTarget();
        while (_threadStarted){
            try{
                Thread.sleep(100);
            }
            catch (Exception e)
            {

            }
        }
        fsin = null;
        real = null;
        imag = null;
        _postBuffer = null;
        _recordBuffer = null;
    }

    private void _safeCallBackFail(IDetectCallback callback, float dB, String exception, boolean finish){
        try{
            if (finish){
                _isDetecting = false;
            }
            callback.onDetect(dB,null,new Exception(exception));
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void _safeCallBack(IDetectCallback callback, float dB, String json, Exception error, boolean finish){
        try{
            if (finish){
                _isDetecting = false;
            }
            callback.onDetect(dB,json,error);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    void _doInitContext(Context context){
        JSONObject deviceInfo = new JSONObject();

        try{
            deviceInfo.put("brand",Build.BRAND);
            deviceInfo.put("model",Build.MODEL);
            deviceInfo.put("version", Build.VERSION.SDK_INT);
        }catch (Exception e){

        }

        if (context != null){
            PackageManager pkgManager = context.getPackageManager();
            _hasMicphonePermission = pkgManager.checkPermission(Manifest.permission.RECORD_AUDIO, context.getPackageName()) == PackageManager.PERMISSION_GRANTED;

            try{
                String androidID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (androidID == null)
                    androidID = "";
                deviceInfo.put("android_id",androidID);
            }catch (Exception e){

            }

            try{
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED){
                    TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(context.TELEPHONY_SERVICE);
                    String imei = telephonyManager.getDeviceId();
                    if (imei != null)
                        deviceInfo.putOpt("imei",imei);
                }
            }catch (Exception e){

            }

            try{
                String mac = getNewMac();
                if (mac != null)
                    deviceInfo.put("mac",mac);
            }catch (Exception e){

            }
        }
        _deviceInfo = deviceInfo.toString();
        Log.d(TAG,_deviceInfo);
    }

    private void _set_sdk_info(String[] info){
        _appKey = info[0];
        _isSandbox = info[1] == "1" ? true: false;
        _tokenURL = info[2];
    }

    private void _set_user_id(String[] info){
        _phone = info[0];
        _userID = info[1];
    }

    private void _record(int source, int duration, IRecordCallback callback){
        if (callback == null)   return;
        Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, source, duration,callback);
        msg.sendToTarget();
    }

    private void _doRecord(int source, int duration, IRecordCallback callback){

    }
    //////////////////////////////////////////////////////////////////
    private static int fft(float[] fr, float[] fi, int m, int inv){
        int mr,nn,i,j,l,k,istep,n,scale,shift;
        float qr,qi,tr,ti,wr,wi;

        n = 1<<m;

        if(n > N_WAVE)
            return -1;

        mr = 0;
        nn = n - 1;
        scale = 0;

        for(m=1; m<=nn; ++m) {
            l = n;
            do {
                l >>= 1;
            } while(mr+l > nn);
            mr = (mr & (l-1)) + l;

            if(mr <= m) continue;
            tr = fr[m];
            fr[m] = fr[mr];
            fr[mr] = tr;
            ti = fi[m];
            fi[m] = fi[mr];
            fi[mr] = ti;
        }

        l = 1;
        k = LOG2_N_WAVE-1;
        while(l < n) {
            if(inv > 0) {
                shift = 0;
            } else {
                shift = 1;
            }
            istep = l << 1;
            float h = 0.5f;
            for(m=0; m<l; ++m) {
                j = m << k;
                wr =  fsin[j+N_WAVE/4];
                wi = -fsin[j];
                if(inv > 0)
                    wi = -wi;
                if(shift > 0) {
                    wr *= h;
                    wi *= h;
                }
                for(i=m; i<n; i+=istep) {
                    j = i + l;
                    tr = wr*fr[j]-wi*fi[j];
                    ti = wr*fi[j]+wi*fr[j];
                    qr = fr[i];
                    qi = fi[i];
                    if(shift > 0) {
                        qr *= h;
                        qi *= h;
                    }
                    fr[j] = qr - tr;
                    fi[j] = qi - ti;
                    fr[i] = qr + tr;
                    fi[i] = qi + ti;
                }
            }
            --k;
            l = istep;
        }

        return scale;
    }

    private static void window_hanning(float[] fr, int n) {
        int j = N_WAVE/n;
        for(int i=0, k=N_WAVE/4; i<n; ++i,k+=j)
            fr[i] *= 0.5f-0.5f*fsin[k%N_WAVE];
    }

    private static int compress(float []input, ByteBuffer output, int numberOfSamples) {
        float max = -99999999f, min = 99999999f;

        for (int index = 0; index < numberOfSamples; ++index) {
            float temp = input[index];
            if (temp > max)
                max = temp;
            if (temp < min)
                min = temp;
        }
        float range = (max - min);
        float average = (max + min) / 2;
        float factor = range / 256;

        try {
            output.putFloat(average * 2);
            output.putFloat(factor * 2);

            for (int index = 0; index < numberOfSamples; ++index) {
                float temp = input[index] - average;
                int result = (int) (temp / factor);
                if (result > 127)
                    result = 127;
                else if (result < -128)
                    result = -128;
                output.putChar((char)result);
            }
            return 8 + numberOfSamples;
        }catch (Exception e){
            return 0;
        }
    }

    private static String toURLEncoded(String paramString) {
        if (paramString == null || paramString.equals("")) {
            return "";
        }

        try
        {
            String str = new String(paramString.getBytes(), "UTF-8");
            str = URLEncoder.encode(str, "UTF-8");
            return str;
        }
        catch (Exception localException)
        {
            Log.e("encode","toURLEncoded error:"+paramString + localException.getLocalizedMessage());
        }

        return "";
    }

    private static String getNewMac() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : all) {
                if (!nif.getName().equalsIgnoreCase("wlan0")) continue;

                byte[] macBytes = nif.getHardwareAddress();
                if (macBytes == null) {
                    return null;
                }

                StringBuilder res1 = new StringBuilder();
                for (byte b : macBytes) {
                    res1.append(String.format("%02X:", b));
                }

                if (res1.length() > 0) {
                    res1.deleteCharAt(res1.length() - 1);
                }
                return res1.toString();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

}

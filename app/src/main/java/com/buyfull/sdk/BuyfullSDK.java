package com.buyfull.sdk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.buyfull.sdk.BuyfullRecorder.DEFAULT_LIMIT_DB;
import static com.buyfull.sdk.BuyfullRecorder.DEFAULT_RECORD_TIMEOUT;

public class BuyfullSDK {
    private static final String     TAG = "BUYFULL_SDK";
    private static final boolean    DEBUG = false;
    private static final String     SDK_VERSION = "1.0.4";
    /**
     * 此方法为DEMO，请自行修改
     * 将参数打包后发送检测请求，返回JSON字符串
     * @param fetchURL          动听返回的URL
     * @param appkey            动听的APPKEY
     * @param deviceInfo        设备信息
     * @param customData        可为空
     * @return JSON结果
     */
    public String detectRequest(String fetchURL, String appkey, String deviceInfo, String customData) throws Exception{
        if (fetchURL == null || appkey == null || deviceInfo == null){
            throw new Exception("Please check params");
        }
        HttpURLConnection connection = null;
        Exception error = null;
        try {
            String cmd = "?url=" + toURLEncoded(fetchURL) + "&appkey=" + toURLEncoded(appkey) + "&platform=android" + "&device_id=" + toURLEncoded(deviceInfo);
            if (customData != null){
                cmd += ("&customdata=" + toURLEncoded(customData));
            }
            URL url = new URL(_detectURL + cmd);

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
            if (code == 200){
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line = null;
                while ((line = reader.readLine()) != null) {
                    msg += line + "\n";
                }
                reader.close();
            }
            connection.disconnect();
            if (DEBUG)
                Log.d(TAG,msg);
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

    public interface IDetectCallback {
        /**
         * 检测完成后回调
         * @param options   此次录音的参数
         * @param dB        此次录音的分贝数，如果小于LIMIT_DB则不会上传检测
         * @param result    检测成功返回的数据，可能为空
         * @param error     如果有错误则不为空
         */
        void onDetect(final JSONObject options, final float dB, final String result, final Exception error);
    }


    private class DetectContext{
        public Handler              callbackHandler;
        public IDetectCallback      callback;
        public String               customData;
        public long                 timeStamp;
        public long                 timeOut = DEFAULT_RECORD_TIMEOUT;
        public boolean              alwaysAutoRetry = false;//如果解析失败是否自动重试
        public boolean              firstTimeBoost = false;//第一次解析是否加速
        public JSONObject           options;

        /**
         * 此方法为DEMO，请自行修改
         * 将参数打包后发送检测请求，返回JSON字符串
         * @param fetchURL          动听返回的URL
         * @param appkey            动听的APPKEY
         * @param deviceInfo        设备信息
         * @param customData        可为空
         * @return JSON结果
         */
        public String detectRequest(String fetchURL, String appkey, String deviceInfo, String customData) throws Exception{
            if (fetchURL == null || appkey == null || deviceInfo == null){
                throw new Exception("Please check params");
            }
            HttpURLConnection connection = null;
            Exception error = null;
            try {
                String cmd = "?url=" + toURLEncoded(fetchURL) + "&appkey=" + toURLEncoded(appkey) + "&platform=android" + "&device_id=" + toURLEncoded(deviceInfo);
                if (customData != null){
                    cmd += ("&customdata=" + toURLEncoded(customData));
                }
                URL url = new URL(_detectURL + cmd);

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
                if (code == 200){
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        msg += line + "\n";
                    }
                    reader.close();
                }
                connection.disconnect();
                if (DEBUG)
                    Log.d(TAG,msg);
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


        public DetectContext(JSONObject _options, IDetectCallback cb){
            callback = cb;
            options = _options;
            timeStamp = System.currentTimeMillis();
            callbackHandler = new Handler();
            try {
                //允许的option的值
                if (options != null){
                    customData = options.optString("customData");
                    timeOut = options.optLong("timeout", DEFAULT_RECORD_TIMEOUT);
                    alwaysAutoRetry = options.optBoolean("alwaysAutoRetry", false);
                    firstTimeBoost = options.optBoolean("firstTimeBoost", false);
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        public JSONObject getRecorderOptions(){
            JSONObject recorderResult = new JSONObject();
            try {
                recorderResult.put("cxt", this);
                if (!options.isNull("limitdB"))
                    recorderResult.put("limitdB",options.get("limitdB"));

                if (!options.isNull("timeout"))
                    recorderResult.put("timeout",options.get("timeout"));

                boolean stopAfterReturn = options.optBoolean("stopAfterReturn",false);
                if (firstTimeBoost && !_hasSuccessGotResult && stopAfterReturn){
                    recorderResult.put("stopAfterReturn", false);
                }else if (!options.isNull("stopAfterReturn")){
                    recorderResult.put("stopAfterReturn",options.get("stopAfterReturn"));
                }

                if (!options.isNull("validTimePeriod"))
                    recorderResult.put("validTimePeriod",options.get("validTimePeriod"));

            }catch (Exception e){

            }
            return recorderResult;
        }
    }
    public synchronized static BuyfullSDK getInstance(){
        if (instance == null){
            instance = new BuyfullSDK();
            instance.init();
        }
        return instance;
    }

    public synchronized static void destory(){
        if (instance != null){
            instance.stop();
            instance = null;
        }
        BuyfullRecorder.destory();
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
     * appkey请向动听员工询问，tokenURL需要自行布署
     * @param appKey
     * @param tokenURL
     * @param detectURL
     */
    public void setSDKInfo(String appKey, String tokenURL, String detectURL){
        Message msg = _notifyThread.mHandler.obtainMessage(SET_SDKINFO, new String[]{appKey, tokenURL, detectURL});
        msg.sendToTarget();
    }

    /**
     * 当前是否开始检测，可以在重试时检测
     * @return
     */
    public synchronized boolean isStarted(){
        return _detectStarted;
    }

    /**
     * 当前是否正在录音检测，不能重复调用detect
     * @return
     */
    public synchronized boolean isDetecting(){
        return _isDetecting;
    }

    /**
     * 启动检测
     */
    public void detect(JSONObject options, final IDetectCallback callback){
        if (callback == null)   return;
        if (isDetecting()){
            callback.onDetect(options, DEFAULT_LIMIT_DB,null, new Exception("don't call detect while detecting"));
            return;
        }
        _detectStarted = true;
        Message msg = _notifyThread.mHandler.obtainMessage(DETECT, new DetectContext(options,callback));
        msg.sendToTarget();
    }
    /**
     * 停止检测，停止后会回调，请注意
     */
    public void stop(){
        _detectStarted = false;
        BuyfullRecorder.getInstance().stop();
    }

    private void _detect(DetectContext cxt, boolean isRetry){
        //以免重复调用
        if (_token == null && _isInitingToken){
            _safeCallBackFail(cxt, DEFAULT_LIMIT_DB, "initing buyfull sdk, please wait", false);
            return;
        }
        if (_token != null && _isDetecting && !isRetry){
            _safeCallBackFail(cxt, DEFAULT_LIMIT_DB, "buyfull sdk is detecting, please wait", false);
            return;
        }

        if (!_hasMicphonePermission){
            _safeCallBackFail(cxt, DEFAULT_LIMIT_DB, "no record permission", false);
            return;
        }

        if (_deviceInfo == null){
            _safeCallBackFail(cxt, DEFAULT_LIMIT_DB, "please init context first", false);
            return;
        }

        if ((_token == null && !_isInitingToken) || _needRefreshToken){
            _isInitingToken = true;
            try {
                String tokenResult = requestToken(_tokenURL,_appKey, _needRefreshToken);
                JSONObject tokenJSON = (JSONObject) new JSONTokener(tokenResult).nextValue();
                String token = tokenJSON.getString("token");
                if (token != null && !token.isEmpty()){
                    _token = token;
                    _needRefreshToken = false;
                    if (DEBUG){
                        Log.d(TAG,"token is:" + token);
                    }

                }else{
                    _safeCallBackFail(cxt, DEFAULT_LIMIT_DB, "init token fail: " + tokenResult, false);
                }
            } catch (Exception e) {
                _safeCallBack(cxt,DEFAULT_LIMIT_DB,null,e, false);
            } finally {
                _isInitingToken = false;
            }
        }

        if (_token != null && !_token.isEmpty() && _hasMicphonePermission && (!_isDetecting || isRetry)) {
            _isDetecting = true;
            BuyfullRecorder.getInstance().record(cxt.getRecorderOptions(), _notifyThread);
        }
    }

    private void onRecord(DetectContext cxt, float dB, byte[] bin, BuyfullRecorder.RecordException error){
        //如果录音返回出错
        if (error != null){
            if (DEBUG) {
                error.printStackTrace();
            }
            _safeCallBackFail(cxt,dB,"record_fail", true);
            return;
        }
        //如果压缩录音返回出错
        if (bin == null){
            _safeCallBackFail(cxt,dB,"record_fail", true);
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "pcm db is " + dB);
        }

        //发送录音检测请求
        String record_id_result = null;
        try {
            record_id_result = recordRequest(bin, _token);
        } catch (Exception e) {
            _safeCallBack(cxt,dB,null, e,true);
        }
        if (record_id_result == null || record_id_result == ""){
            _safeCallBackFail(cxt,dB,"get record id fail", true);
            return;
        }

        //处理录音检测结果
        JSONObject record_id_json = null;
        String record_id_url = null;
        try{
            record_id_json = (JSONObject) new JSONTokener(record_id_result).nextValue();
            String msg = record_id_json.getString("msg");
            if (!msg.equals("ok") && !msg.equals("no_result")){
                Log.e(TAG,"server return error msg:" + record_id_result);
            }
            if (msg.equals("ok")){
                //有检测结果
                record_id_url = record_id_json.getString("query_url");
                if (record_id_url == null || record_id_url.equals("")){
                    _safeCallBackFail(cxt,dB,"server_error:" + record_id_result, true);
                    return;
                }
                _hasSuccessGotResult = true;
            }else if (msg.equals("token_error")){
                //token有问题
                _needRefreshToken = true;
                _safeCallBackFail(cxt,dB,"token_error", true);
                return;
            }else {
                String record_id = record_id_json.getString("record_id");
                if (msg.equals("no_result") || msg.equals("db_too_low")){
                    //没有检测结果，可以自动重试
                    if ((cxt.alwaysAutoRetry || (cxt.firstTimeBoost && !_hasSuccessGotResult) )&& ((System.currentTimeMillis() - cxt.timeStamp) < cxt.timeOut)) {
                        if (DEBUG){
                            Log.d(TAG,"Auto retry");
                        }
                        Message msg2 = _notifyThread.mHandler.obtainMessage(RETRY, cxt);
                        msg2.sendToTarget();
                        return;
                    }
                    _safeCallBack(cxt,dB, record_id, new Exception("no_result"), true);
                }else{
                    //其它问题
                    _safeCallBack(cxt,dB, record_id, new Exception("server_error"), true);
                }

                return;
            }
        }catch (Exception e){
            if (DEBUG) {
                Log.d(TAG, "server return invalid record id result:" + record_id_result);
            }
            _safeCallBackFail(cxt,dB,"server return invalid record id result" + record_id_result, true);
            return;
        }

        //发送请求给业务服务器查询，detectRequest请自行修改
        String result = null;
        try {
            result = detectRequest(record_id_url, _appKey,_deviceInfo,cxt.customData);
        } catch (Exception e) {
            _safeCallBack(cxt,dB,null, e,true);
        }

        _safeCallBack(cxt,dB,result,null,true);
    }
    /**
     * 请求TOKEN，有了TOKEN后才能使用BUYFULL SDK
     * @param tokenURL      需要自行布署
     * @param appkey        请在动听官网申请，并询问动听工作人员
     * @return              token
     */
    public String requestToken(String tokenURL, String appkey, boolean refresh) throws Exception{
        if (tokenURL == null || appkey == null){
            throw new Exception("Please check params");
        }
        HttpURLConnection connection = null;
        Exception error = null;
        try {
            String urlString = tokenURL + "?appkey=" + toURLEncoded(appkey);
            if (refresh){
                urlString += "&refresh=true&oldtoken=" + toURLEncoded(_token);
            }
            URL url = new URL(urlString);
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
            if (DEBUG)
                Log.d(TAG,msg);
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
     * 将参数打包后发送检测请求，返回record_id
     * @param binData buildBin返回的二进制
     * @param token             tokenURL返回的token
     * @return JSON结果
     */
    public String recordRequest(byte[] binData, String token) throws Exception {
        if (binData == null || binData.length <= 0 || token == null){
            throw new Exception("Please check params");
        }
        HttpURLConnection connection = null;
        Exception error = null;
        try {
            String cmd = "token="+ toURLEncoded( token) + "&sdkversion=" + toURLEncoded(SDK_VERSION) + "&sdktype=Android";
            URL url = new URL("https://api.euphonyqr.com/api/decode/v1?" + cmd);

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
            outputStream.flush();
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
            if (DEBUG)
                Log.d(TAG,msg);
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

    ////////////////////////////////////////////////////////////////////////
    private LooperThread                    _notifyThread;
    private volatile static BuyfullSDK      instance;
    private volatile boolean                _detectStarted;
    private volatile boolean                _isDetecting;
    private volatile boolean                _isInitingToken;
    private volatile boolean                _needRefreshToken;
    private volatile boolean                _hasMicphonePermission;
    private String                          _appKey;
    private String                          _tokenURL;
    private String                          _detectURL;
    private String                          _token;
    private String                          _deviceInfo;
    private boolean                         _hasSuccessGotResult;

    private static final int INIT_MSG = 1;
    private static final int DESTORY = 2;
    private static final int SET_SDKINFO = 3;
    private static final int SET_USER_ID = 4;
    private static final int DETECT = 5;
    private static final int RETRY = 6;
    private static final int DEBUG_UPLOAD = 100;

    private static class LooperThread extends Thread implements BuyfullRecorder.IRecordCallback {
        public Handler mHandler;
        public volatile boolean threadStarted;
        public volatile boolean threadEnded;
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


                        case DETECT:
                            if (instance != null)
                                instance._detect((DetectContext)msg.obj, false);
                            break;

                        case RETRY:
                            if (instance != null)
                                instance._detect((DetectContext)msg.obj, true);
                            break;

                        case DESTORY:
                        default:
                            Looper.myLooper().quit();
                    }
                }
            };
            threadStarted = true;
            Looper.loop();
            threadEnded = false;
        }

        @Override
        public void onRecord(JSONObject options, float dB, byte[] bin, BuyfullRecorder.RecordException error) {
            if (instance == null)
                return;
            DetectContext cxt = null;
            try {
                cxt = (DetectContext)options.get("cxt");
            }catch (Exception e){
                e.printStackTrace();
                return;
            }
            options.remove("cxt");
            if (cxt == null){
                Log.e(TAG,"cxt is null, not allowed");
                return;
            }
            instance.onRecord(cxt, dB, bin, error);
        }
    }
    private void init(){
        _notifyThread = new LooperThread("BuyfullLoop");
        _notifyThread.start();
        while (!_notifyThread.threadStarted)
        {
            try{
                Thread.sleep(100);
            }
            catch (Exception e)
            {

            }
        }
    }

    private void _safeCallBackFail(DetectContext cxt, final float dB, String exception, boolean finish){
        try{
            if (finish){
                _isDetecting = false;
                _detectStarted = false;
                if (DEBUG){
                    Log.d(TAG,"Detect use time: " + (System.currentTimeMillis() - cxt.timeStamp));
                }
            }
            final IDetectCallback cb = cxt.callback;
            final JSONObject options = cxt.options;
            final Exception err = new Exception(exception);
            Handler handler = cxt.callbackHandler;

            cxt.callback = null;
            cxt.callbackHandler = null;
            cxt.options = null;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        cb.onDetect(options, dB,null,err);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
            });
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void _safeCallBack(DetectContext cxt,final float dB,final String json,final Exception error, boolean finish){
        try{
            if (finish){
                _isDetecting = false;
                _detectStarted = false;
                if (DEBUG){
                    Log.d(TAG,"Detect use time: " + (System.currentTimeMillis() - cxt.timeStamp));
                }
            }
            final IDetectCallback cb = cxt.callback;
            final JSONObject options = cxt.options;
            Handler handler = cxt.callbackHandler;

            cxt.callback = null;
            cxt.callbackHandler = null;
            cxt.options = null;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        cb.onDetect(options, dB,json,error);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
            });

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
        if (DEBUG)
            Log.d(TAG,_deviceInfo);
    }

    private void _set_sdk_info(String[] info){
        _appKey = info[0];
        _tokenURL = info[1];
        _detectURL = info[2];
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

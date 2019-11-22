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
import java.lang.ref.WeakReference;
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
    private static final String     SDK_VERSION = "1.0.2";

    public interface IDetectCallback {
        /**
         * 检测完成后回调
         * @param options   此次录音的参数
         * @param dB        此次录音的分贝数，如果小于LIMIT_DB则不会上传检测
         * @param json      检测成功返回JSON数据，可能为空
         * @param error     如果有错误则不为空
         */
        void onDetect(final JSONObject options, final float dB, final String json, final Exception error);
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
                recorderResult.put("cxt", new WeakReference<DetectContext>(this));
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

    public void detect(JSONObject options, final IDetectCallback callback){
        if (callback == null)   return;
        if (isDetecting()){
            callback.onDetect(options, DEFAULT_LIMIT_DB,null, new Exception("don't call detect while detecting"));
            return;
        }
        Message msg = _notifyThread.mHandler.obtainMessage(DETECT, new DetectContext(options,callback));
        msg.sendToTarget();
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

        if (_token == null && !_isInitingToken){
            _isInitingToken = true;
            try {
                String tokenResult = requestToken(_tokenURL,_appKey,_isSandbox);
                JSONObject tokenJSON = (JSONObject) new JSONTokener(tokenResult).nextValue();
                String token = tokenJSON.getString("token");
                if (token != null && !token.isEmpty()){
                    _token = token;
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
        if (error != null){
            _safeCallBack(cxt,dB,null, error,true);
            return;
        }
        if (bin == null){
            _safeCallBackFail(cxt,dB,"record fail", true);
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "pcm db is " + dB);
        }

        String rawJson = null;
        try {
            rawJson = detectRequest(bin,_appKey,_token,_isSandbox,_deviceInfo,_phone,_userID,cxt.customData);
        } catch (Exception e) {
            _safeCallBack(cxt,dB,null, e,true);
        }

        String jsonResult = null;
        try {
            jsonResult = handleJSONResult(rawJson);
        } catch (Exception e) {
            _safeCallBack(cxt,dB,null, e,true);
        }

        if ((cxt.alwaysAutoRetry || (cxt.firstTimeBoost && !_hasSuccessGotResult) )&& ((System.currentTimeMillis() - cxt.timeStamp) < cxt.timeOut)){
            //check if alltags is empty, and autoretry if not timeout
            boolean needRetry = true;

            if (jsonResult != null){
                try{
                    JSONObject jsonObj = (JSONObject) new JSONTokener(jsonResult).nextValue();
                    int tagCount = jsonObj.getInt("count");
                    if (tagCount > 0)  {
                        _hasSuccessGotResult = true;
                        needRetry = false;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            if (needRetry){
                if (DEBUG){
                    Log.d(TAG,"Auto retry");
                }
                Message msg = _notifyThread.mHandler.obtainMessage(RETRY, cxt);
                msg.sendToTarget();
                return;
            }
        }

        _safeCallBack(cxt,dB,jsonResult,null,true);
    }
    /**
     * 请求TOKEN，有了TOKEN后才能使用BUYFULL SDK
     * @param tokenURL      需要自行布署
     * @param appkey        请在动听官网申请，并询问动听工作人员
     * @param isSandbox     如果是在sandbox.euphonyqr.com申请的appkey为true，否则为false
     * @return              token
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
            String cmd = "soundtag-decode/decodev7/Android/BIN/" + toURLEncoded(json);
            URL url = new URL("https://api.euphonyqr.com/api/decode2?cmd=" + cmd);

            url = new URL("https://testeast.euphonyqr.com/test/api/decode_test?cmd=" + cmd);
            url = new URL("http://192.168.110.6:8081/api/decode2?cmd=" + cmd);
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

    private class DecodeResult{
        public int channel;
        public float power;
        public float distance;
        public float range;
    }
    private Comparator<DecodeResult> _decodeComparator = new Comparator<DecodeResult>() {
        @Override
        public int compare(DecodeResult o1, DecodeResult o2) {
            float score1 = o1.power;
            float score2 = o2.power;
            if (score1 > score2)
                return -1;
            else if (score1 < score2)
                return 1;
            return 0;
        }
    };

    /*
     * 处理服务器返回的原始JSON，加入一些辅助数据
     * @param rawJSON       服务器返回的原始JSON
     * @return              处理后的JSON，可能为空
     */
    public String handleJSONResult(String rawJSON) throws Exception{
        if (rawJSON == null){
            return null;
        }
        JSONObject old_json_result = null;
        JSONArray old_result = null;
        try{
            old_json_result = (JSONObject) new JSONTokener(rawJSON).nextValue();
            old_result = old_json_result.getJSONArray("result");
        }catch (Exception e){
            Log.d(TAG,"server return invalid result:" + rawJSON);
            e.printStackTrace();
            return null;
        }

        String requestID = old_json_result.getString("reqid");
        if (old_result == null || requestID == null || requestID.isEmpty()){
            return null;
        }

        Set<String> validTagset = new HashSet<String>();
        JSONArray allTags = new JSONArray();
        JSONArray rawResults = new JSONArray();
        JSONArray sortedResults = new JSONArray();
        JSONArray validResults = new JSONArray();
        DecodeResult[] decodeResults = new DecodeResult[old_result.length()];

        for (int index = 0;index < old_result.length(); ++index) {
            JSONObject raw_result = old_result.getJSONObject(index);
            decodeResults[index] = new DecodeResult();
            if (raw_result.has("channel")){
                decodeResults[index].channel = raw_result.getInt("channel");
            }else{
                decodeResults[index].channel = index;
                raw_result.put("channel", index);
            }
            if (raw_result.has("distance")){
                decodeResults[index].distance = (float) raw_result.getDouble("distance");
            }
            if (raw_result.has("range")){
                decodeResults[index].range = (float) raw_result.getDouble("range");
            }
            decodeResults[index].power = (float) raw_result.getDouble("power");

            rawResults.put(raw_result);
        }
        Arrays.sort(decodeResults, _decodeComparator);

        for (int index = 0; index < decodeResults.length; ++index){
            int channel = decodeResults[index].channel;
            JSONObject raw_result = old_result.getJSONObject(channel);
            sortedResults.put(raw_result);
            JSONArray tags = raw_result.getJSONArray("tags");
            if (tags.length() > 0){
                validResults.put(raw_result);
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
        result.put("count",validResults.length());
        result.put("allTags",allTags);
        return result.toString();
    }

    ////////////////////////////////////////////////////////////////////////
    private LooperThread                    _notifyThread;
    private volatile static BuyfullSDK      instance;
    private volatile boolean                _isDetecting;
    private volatile boolean                _isInitingToken;
    private volatile boolean                _hasMicphonePermission;
    private String                          _appKey;
    private boolean                         _isSandbox;
    private String                          _tokenURL;
    private String                          _token;
    private String                          _deviceInfo;
    private String                          _userID;
    private String                          _phone;
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

                        case SET_USER_ID:
                            if (instance != null)
                                instance._set_user_id((String[])msg.obj);
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
                cxt = ((WeakReference<DetectContext>)options.get("cxt")).get();
            }catch (Exception e){
                e.printStackTrace();
                return;
            }
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
        _isSandbox = info[1] == "1" ? true: false;
        _tokenURL = info[2];
    }

    private void _set_user_id(String[] info){
        _phone = info[0];
        _userID = info[1];
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

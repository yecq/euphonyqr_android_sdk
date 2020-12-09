package com.buyfull.sdk;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import org.json.JSONObject;


import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;


import static android.media.AudioRecord.RECORDSTATE_RECORDING;

public class BuyfullRecorder {
    public static final int NO_ERROR = 0;
    public static final int RECORD_STOPED = 1;//结束录音
    public static final int RECORD_FAIL = 2;//录音失败
    public static final int NETWORK_ERROR = 3;//网络错误
    public static final int RECORD_TIMEOUT = 4;//录音超时
    public static final int NO_RECORD_PERMISSION = 5;//没有录音权限
    public static final int SIGNAL_DB_TOO_LOW = 6;//录音分贝数太低 <-125，不上传服务器检测

    public static final float   DEFAULT_LIMIT_DB = -125f; //分贝阈值，低于此值不上传判断
    public static final long    DEFAULT_RECORD_TIMEOUT = 6000; //默认录音超时
    public static final long    DEFAULT_VALID_TIME_PERIOD = 1000; //默认上次录音的有效时间
    public static final int     DEFAULT_RECORD_SAMPLE_RATE = 48000;

    public class RecordException extends Exception{
        public int code;//见上面定义
        public RecordException(int _code, Exception error) {
            super(error);
            code = _code;
        }
        public RecordException(int _code, String message) {
            super(message);
            code = _code;
        }
    }

    public interface IRecordCallback {
        /**
         * 录音结束后回调
         * @param options   调用时的参数
         * @param dB        此次录音的分贝数
         * @param bin       压缩后BIN数据
         * @param error     如果有错误则不为空
         */
        void onRecord(final JSONObject options, final float dB, final byte[] bin, final RecordException error);
    }

    private class RecordContext{
        public Handler              callbackHandler;
        public IRecordCallback      callback;
        public JSONObject           options;
        public long                 timeStamp;
        public float                limitDB = DEFAULT_LIMIT_DB;
        public long                 timeOut = DEFAULT_RECORD_TIMEOUT;
        public long                 validTimePeriod = DEFAULT_VALID_TIME_PERIOD;
        public boolean              stopAfterReturn = false;//是否在录音返回后自动停止录音

        public RecordContext(JSONObject _options, IRecordCallback cb){
            callback = cb;
            options = _options;
            timeStamp = System.currentTimeMillis();
            callbackHandler = new Handler();
            try {
                //允许的option的值
                if (options != null){
                    limitDB = (float) options.optDouble("limitdB", DEFAULT_LIMIT_DB);
                    timeOut = options.optLong("timeout", DEFAULT_RECORD_TIMEOUT);
                    stopAfterReturn = options.optBoolean("stopAfterReturn", false);
                    validTimePeriod = options.optLong("validTimePeriod", DEFAULT_VALID_TIME_PERIOD);
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }
    /**
     * 单例
     */
    public synchronized static BuyfullRecorder getInstance(){
        if (instance == null){
            instance = new BuyfullRecorder();
            instance.init();
        }
        return instance;
    }

    public synchronized static void destory(){
        if (instance != null){
            instance._destory();
            instance = null;
        }
    }

    /**
     * 当前是否正在录音检测
     * @return
     */
    public synchronized boolean isRecording(){
        return _hasTestFinished() && _recorder != null && _recorder.getRecordingState() == RECORDSTATE_RECORDING;
    }
    /**
     *  录音并且返回压缩BIN数据，默认录音参数为48000,16bit,单声道，时长1.1秒。
     * @param options
     * @param callback
     */
    public void record(JSONObject options, IRecordCallback callback){
        if (callback == null)   return;
        _recordStoped = false;
        RecordContext cxt = new RecordContext(options, callback);
        Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, -1, -1,cxt);
        msg.sendToTarget();
    }

    public void stop(){
        _recordStoped = true;
        Message msg = _notifyThread.mHandler.obtainMessage(STOP_RECORD);
        msg.sendToTarget();
    }

    ////////////////////////////////////////////////////////////////////////
    private static final String     TAG = "BUYFULL_RECORDER";
    private static final boolean    DEBUG = false;

    private static final float Pi = 3.14159265358979f;
    private static final int N_WAVE = (64*1024);
    private static final int LOG2_N_WAVE = (6+10);
    private static final String SDK_VERSION = "1.1.0";

    private volatile static BuyfullRecorder instance;
    private static float                    fsin[];
    private float[]                         real;
    private float[]                         imag;
    private LooperThread                    _notifyThread;
    private LooperThread                    _recordThread;
    private volatile byte[]                 _recordBuffer;
    private static class RecordData{
        public byte[] data;
        public long timeStamp;
    }
    private ConcurrentLinkedQueue<RecordData>   _tempRecordBuffer;
    private ByteBuffer                      _binBuffer;
    private volatile AudioRecord            _recorder;
    private volatile long                   _lastBufferTimeStamp;
    private volatile byte[]                 _lastPCMData;
    private volatile boolean                _recordStoped;

    private static final int START_RECORD = 1;
    private static final int STOP_RECORD = 2;
    private static final int START_TEST_RECORD = 3;
    private static final int FETCH_BUFFER = 4;
    private static final int UPDATE_BUFFER = 5;
    private static final int DESTORY = 6;

    private static class LooperThread extends Thread{
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
                        case START_RECORD:
                            if (instance != null){
                                if (!instance._hasTestFinished()){
                                    instance._doTestRecord(msg.arg1,msg.arg2,(RecordContext) msg.obj);
                                }else {
                                    instance._doRecord(msg.arg1,msg.arg2,(RecordContext)msg.obj);
                                }
                            }
                            break;

                        case STOP_RECORD:
                            if (instance != null){
                                if (instance._hasTestFinished()) {
                                    instance._doStop();
                                }}

                            break;

                        case START_TEST_RECORD:
                            if (instance != null) {
                                instance._doTestRecord(msg.arg1,msg.arg2,(RecordContext)msg.obj);
                            }
                            break;

                        case FETCH_BUFFER:
                            if (instance != null) {
                                instance._fetchBuffer((RecordContext)msg.obj);
                            }
                            break;

                        case UPDATE_BUFFER:
                            if (instance != null) {
                                instance._updateBuffer(instance._recorder);
                            }
                            break;

                        case DESTORY:
                        default:
                            threadEnded = true;
                            Looper.myLooper().quit();
                    }
                }
            };
            threadStarted = true;
            Looper.loop();
            threadEnded = true;
        }
    }

    private BuyfullRecorder(){
        _tempRecordBuffer = new ConcurrentLinkedQueue<RecordData>();
        _recordBuffer = new byte[230 * 1024];
        _binBuffer = ByteBuffer.allocate(8 * 1024).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void init(){
        Log.v(TAG,"Buyfull recorder version:" + SDK_VERSION);
        fsin = new float[N_WAVE];
        real = new float[N_WAVE];
        imag = new float[N_WAVE];

        _initRecordConfig();
        _notifyThread = new LooperThread("BuyfullRecorder1");
        _notifyThread.start();

        _recordThread = new LooperThread("BuyfullRecorder2");
        _recordThread.setPriority(Thread.MAX_PRIORITY);
        _recordThread.start();

        while (!(_notifyThread.threadStarted && _recordThread.threadStarted))
        {
            try{
                Thread.sleep(100);
            }
            catch (Exception e)
            {

            }
        }

        for (int i=0; i<N_WAVE; i++){
            fsin[i] = (float)Math.sin(2*Pi/N_WAVE*i);
        }

        Message msg = _recordThread.mHandler.obtainMessage(UPDATE_BUFFER);
        _recordThread.mHandler.sendMessage(msg);
    }

    private void _destory(){
        _notifyThread.mHandler.obtainMessage(DESTORY).sendToTarget();
        _recordThread.mHandler.obtainMessage(DESTORY).sendToTarget();
        while (!(_notifyThread.threadEnded && _recordThread.threadEnded))
        {
            try{
                Thread.sleep(100);
            }
            catch (Exception e)
            {

            }
        }
    }

    private static final float THRESHOLD_DB = -150;
    private static final int THRESHOLD_DELAY = 1000;
    private static final int RECORD_FETCH_FRAMES = 4096;
    private static final int TEST_FETCH_FRAMES = 2048;

    private double toDB(double amp){
        if (amp <= 0 || Double.isNaN(amp) || Double.isInfinite(amp))
            return THRESHOLD_DB;

        return Math.log(amp) * (8.6858896380650365530225783783322);
    }

    private float getDB(byte[] pcmData, int pcmDataSize, int sampleRate, int channels, int bits, boolean isLastFrame) throws Exception{
        if (pcmData == null){
            throw (new Exception("invalid pcmData or outBin:"));
        }
        int stepCount = TEST_FETCH_FRAMES;
        int stepSize = channels * (bits / 8);
        int startIndex = 0;
        if (!(sampleRate == DEFAULT_RECORD_SAMPLE_RATE)){
            throw (new Exception("invalid sample rate:" + sampleRate));
        }else if (channels < 1 || channels > 2){
            throw (new Exception("invalid channel count:" + channels));
        }else if (!(bits == 16 || bits == 32)){
            throw (new Exception("invalid bit count:" + bits));
        }else{
            int minPCMDataSize = stepCount * stepSize;
            if (pcmDataSize < minPCMDataSize){
                throw (new Exception("invalid pcmData length:" + pcmDataSize));
            }else{
                startIndex += (pcmDataSize - minPCMDataSize);
                if (!isLastFrame){
                    startIndex -= sampleRate * stepSize;
                }
            }
        }

        ByteBuffer pcmByte = ByteBuffer.wrap(pcmData, 0, pcmDataSize).order(ByteOrder.LITTLE_ENDIAN);

        boolean allZero = true;
        float[] re = real;
        float[] im = imag;
        Arrays.fill(re,0,stepCount,0);
        Arrays.fill(im,0,stepCount,0);
        for (int index = 0;index < stepCount;++index,startIndex += stepSize){
            if (bits == 16){
                re[index] = (float)(pcmByte.getShort(startIndex)/32768.0);
            }else{
                re[index] = pcmByte.getFloat(startIndex);
            }
            if (re[index] != 0)
                allZero = false;
        }
        if (allZero)
            return THRESHOLD_DB;

        window_hanning(re, stepCount);
        fft(re,im,11,0);
        int s = 836, l = 90;
        if (sampleRate == DEFAULT_RECORD_SAMPLE_RATE){
            s = 768;
            l = 84;
        }
        double db = 0;
        for (int index = 0;index < l;++index){
            double _re = re[s + index];
            double _im = im[s + index];
            db += Math.sqrt(_re * _re + _im * _im);
        }
        db /= l;
        db = toDB(db);

        return (float)db;
    }

    private byte[] buildBin(byte[] pcmData, int sampleRate, int recordPeriodInMS, int channels, int bits) throws Exception{
        if (pcmData == null){
            throw (new Exception("invalid pcmData or outBin:"));
        }
        int pcmDataSize = pcmData.length;
        int stepSize = channels * (bits / 8);
        int startIndex = 0;
        int stepCount = (sampleRate * recordPeriodInMS) / 1000;
        if (recordPeriodInMS < RECORD_PERIOD){
            throw (new Exception("invalid pcmData length:" + pcmDataSize));
        }else{
            stepCount = sampleRate * RECORD_PERIOD / 1000;
            startIndex = (pcmDataSize - stepCount * stepSize);
        }

        int resultSize = stepCount / 8;

        if (_binBuffer.capacity() < (resultSize + 12)){
            throw (new Exception("pcmData is too big"));
        }
        _lastPCMData = pcmData;
        if (!(sampleRate == DEFAULT_RECORD_SAMPLE_RATE)){
            throw (new Exception("invalid sample rate:" + sampleRate));
        }else if (channels < 1 || channels > 2){
            throw (new Exception("invalid channel count:" + channels));
        }else if (!(bits == 16 || bits == 32)){
            throw (new Exception("invalid bit count:" + bits));
        }

        ByteBuffer pcmByte = ByteBuffer.wrap(pcmData, 0, pcmDataSize).order(ByteOrder.LITTLE_ENDIAN);

        float[] re = real;
        float[] im = imag;
        Arrays.fill(re,0);
        Arrays.fill(im,0);
        for (int index = 0;index < stepCount;++index, startIndex += stepSize){
            if (bits == 16){
                re[index] = (float)(pcmByte.getShort(startIndex)/32768.0);
            }else{
                re[index] = pcmByte.getFloat(startIndex);
            }
        }

        fft(re,im,LOG2_N_WAVE,0);

        int s = 26112;
        if (sampleRate == DEFAULT_RECORD_SAMPLE_RATE){
            s = 24064;
        }

        System.arraycopy(re,s,re,0,4096);
        System.arraycopy(im,s,im,0,4096);
        Arrays.fill(re,4096,N_WAVE,0);
        Arrays.fill(im,4096,N_WAVE,0);
        fft(re,im,13,1);

        int finalSize = resultSize + 12;
        _binBuffer.clear();
        _binBuffer.limit(finalSize);
        _binBuffer.mark();
        _binBuffer.position(4);
        resultSize = compress(re, _binBuffer, resultSize);
        if (resultSize > 65535){
            throw (new Exception("too long bin"));
        }
        _binBuffer.reset();
        if (sampleRate == 44100){
            _binBuffer.put((byte) 1);
        }else{
            _binBuffer.put((byte) 2);
        }
        _binBuffer.put((byte) 1);
        _binBuffer.putShort((short) (resultSize & 0xffff));
        byte[] result = new byte[finalSize];
        System.arraycopy(_binBuffer.array(),0,result,0,finalSize);
        if (DEBUG){
            Log.d(TAG,"bin size " + finalSize);
            Log.d(TAG,"bin md5 " + md5Decode32(result));

        }
        return result;
    }

    private class RecordConfig{
        public int index;
        public int src;
        public int duration;
        public float power;
        public int delayTime;
        public int hasFailed;
        public String tag;
        public float getScore(){
            float fail_score = -hasFailed;
            if (fail_score < -10) {
                fail_score = -10;
            }

            float power_score = 0;
            if (power > 0){
                power_score = 70;
            }else if (power <= THRESHOLD_DB){
                power_score = 0;
            }else{
                power_score = ((-THRESHOLD_DB + power) / -THRESHOLD_DB) * 70;
            }

            float start_score = 0;
            if (delayTime <= 0){
                start_score = 30;
            }else if (delayTime >= THRESHOLD_DELAY){
                start_score = 0;
            }else{
                start_score = (float) (((THRESHOLD_DELAY - delayTime) * 30.0) / THRESHOLD_DELAY);
            }
//            Log.d(TAG,tag + " : power score is: " + power_score + " | start_score is: " + start_score + " fail score is: " + fail_score);
            return power_score + start_score + fail_score;
        }
    }
    private RecordConfig _recordConfigs[];
    private RecordConfig _sortedConfigs[];

    private Comparator<RecordConfig> _recordConfigComparator;
    private static int RECORD_CONFIG_COUNT = 5;
    private static final int TEST_PERIOD = 600;
    private static final int RECORD_PERIOD = 1100; //录音时长ms
    private static final int RECORD_CHANNEL = 1;
    private static final int RECORD_BITS = 16;

    private int _recordTestIndex = 0;
    private int _preferSampleRate = DEFAULT_RECORD_SAMPLE_RATE;
    private volatile int _lastPCMSize = 0;
    private volatile String _lastRecordSource = "";
    private volatile int _lastRecordPeriod = 0;
    private volatile int _lastRecordExpectSize = 0;
    private volatile long _lastRecordStartTime = -1;

    private void _initRecordConfig(){
        if (_recordConfigComparator != null)
            return;

        if (Build.VERSION.SDK_INT < 24){
            RECORD_CONFIG_COUNT = 4;
        }else{
            RECORD_CONFIG_COUNT = 5;
        }

        _recordConfigs = new RecordConfig[RECORD_CONFIG_COUNT];
        _sortedConfigs = new RecordConfig[RECORD_CONFIG_COUNT];

        for (int index = 0;index < RECORD_CONFIG_COUNT;++index){
            _recordConfigs[index] = new RecordConfig();
            _recordConfigs[index].index = index;
            _recordConfigs[index].duration = TEST_PERIOD;
            _sortedConfigs[index] = _recordConfigs[index];
        }

        _recordConfigs[0].src = MediaRecorder.AudioSource.MIC;
        _recordConfigs[1].src = MediaRecorder.AudioSource.CAMCORDER;
        _recordConfigs[2].src = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        _recordConfigs[3].src = MediaRecorder.AudioSource.VOICE_COMMUNICATION;

        _recordConfigs[0].tag = "MIC";
        _recordConfigs[1].tag = "CAMCORDER";
        _recordConfigs[2].tag = "VOICE_RECOGNITION";
        _recordConfigs[3].tag = "VOICE_COMMUNICATION";

        if (RECORD_CONFIG_COUNT > 4){
            _recordConfigs[4].src = MediaRecorder.AudioSource.UNPROCESSED;
            _recordConfigs[4].tag = "UNPROCESSED";

        }

        _recordConfigComparator = new Comparator<RecordConfig>() {
            @Override
            public int compare(RecordConfig o1, RecordConfig o2) {
                float score1 = o1.getScore();
                float score2 = o2.getScore();
                if (score1 > score2)
                    return -1;
                else if (score1 < score2)
                    return 1;
                return 0;
            }
        };
    }

    private void _record(int source, int duration, final RecordContext cxt){
        if (_hasTestFinished()){
            Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, source, duration,cxt);
            msg.sendToTarget();
        }else{
            Message msg = _notifyThread.mHandler.obtainMessage(START_TEST_RECORD, source, duration,cxt);
            msg.sendToTarget();
        }
    }

    private void _safeRecordCallBack(RecordContext cxt,final float dB,final byte[] pcm, int errorCode, Exception error, boolean finish){
        try{
            if (finish){
                stop();
            }
            Handler handler = cxt.callbackHandler;
            final RecordException re = (error == null)? null: new RecordException(errorCode, error);
            final JSONObject options = cxt.options;
            final IRecordCallback cb = cxt.callback;

            cxt.callbackHandler = null;
            cxt.options = null;
            cxt.callback = null;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (re != null){
                            cb.onRecord(options, dB,null,re);
                        }else{
                            cb.onRecord(options, dB,pcm,null);
                        }
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
            });
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void _doStop(){
        if (_recorder == null)
            return;

        try {
            _recorder.stop();
        }catch (Exception e){

        }
        try {
            _recorder.release();
        }catch (Exception e){

        }
        _recorder = null;
    }

    private boolean _hasExpired(RecordContext cxt){
        if (_lastBufferTimeStamp < 0){
            return true;
        }
        if ((System.currentTimeMillis() - _lastBufferTimeStamp) > (cxt.validTimePeriod)){
            return true;
        }
        return false;
    }

    private void _doRecord(int source, int duration,final  RecordContext cxt){
        if (!_hasTestFinished()){
            _doTestRecord(source, duration, cxt);
            return;
        }
        if (_recordStoped){
            _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB, null, RECORD_STOPED, new Exception("record use:" + _lastRecordSource + " record stop"), cxt.stopAfterReturn);
            return;
        }
        if (!_hasExpired(cxt)){
            Message msg = _notifyThread.mHandler.obtainMessage(FETCH_BUFFER, cxt);
            msg.sendToTarget();
            return;
        }
        int audioSource = source;
        int recordPeriod = duration;

        int recordIndex = 0;
        if (audioSource < 0){
            recordIndex = _sortedConfigs[0].index;
            audioSource = _recordConfigs[recordIndex].src;
            if (DEBUG)
                Log.d("audio rec", "select audio: " + _recordConfigs[recordIndex].tag + " , power is: " + _recordConfigs[recordIndex].power);
        }

        RecordConfig config = _recordConfigs[recordIndex];
        if (recordPeriod <= 0){
            recordPeriod = config.duration;
        }

        if (config.delayTime >= THRESHOLD_DELAY){
            _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL  , new Exception("record use:"+ config.tag + " is not available"), true);
            return;
        }

        if (_recorder == null){
            _lastBufferTimeStamp = -1;
            try{
                _recorder = new AudioRecord(audioSource, _preferSampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,512 * 1024);
                if (_recorder.getState() != AudioRecord.STATE_INITIALIZED){
                    _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL  ,new Exception("record use:"+ config.tag + " init failed1"), true);
                    return;
                }
            }catch (Exception e){
                _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL  ,  new Exception("record use:"+ config.tag + " init failed1"), true);
                return;
            }
        }
        AudioRecord record = _recorder;

        int realSampleRate = record.getSampleRate();
        if (realSampleRate != _preferSampleRate){
            _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL  ,  new Exception("record use:"+ config.tag + " init failed2"), true);
            return;
        }
        if (record.getRecordingState() != RECORDSTATE_RECORDING){
            _lastBufferTimeStamp = -1;
            try{
                record.startRecording();
            }catch (Exception e){
                try {
                    record.stop();
                }catch (Exception e1){

                }
                try {
                    record.release();
                }catch (Exception e2){

                }
                _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL,e, true);
                return;
            }
            if (record.getRecordingState() != RECORDSTATE_RECORDING){
                _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL,new Exception("record use:"+ config.tag + " init failed3"), true);
                return;
            }

            _lastRecordStartTime = System.currentTimeMillis();
            _lastRecordSource = config.tag;
            _lastRecordPeriod = recordPeriod;
            _lastPCMSize = 0;
            int expectReadSize = (realSampleRate * recordPeriod * (RECORD_BITS / 8)) / 1000;
            if ((expectReadSize % 2) == 1)
                --expectReadSize;
            _lastRecordExpectSize = expectReadSize;
        }
        Message msg = _notifyThread.mHandler.obtainMessage(FETCH_BUFFER, cxt);
        _notifyThread.mHandler.sendMessageDelayed(msg,DEFAULT_VALID_TIME_PERIOD);
    }

    private void _updateBuffer(AudioRecord record){
        LooperThread thisThread = (LooperThread)Thread.currentThread();
        byte[] _recordBuffer = new byte[RECORD_FETCH_FRAMES * 2 * RECORD_BITS / 8];
        while(!thisThread.threadEnded){
            if (!isRecording() || record == null){
                if (DEBUG)
                    Log.d("audio rec", "audio recorder update buffer empty return");
                Message msg = _recordThread.mHandler.obtainMessage(UPDATE_BUFFER);
                _recordThread.mHandler.sendMessageDelayed(msg, 1000);
                return;
            }

            int expect_size = (int)(RECORD_FETCH_FRAMES * RECORD_BITS / 8);

            int readSize = 0;
            try {
                long now = System.nanoTime();
                readSize = record.read(_recordBuffer, 0, expect_size);
                if (readSize < 0){
                    //error
                    _doStop();
                    Message msg = _recordThread.mHandler.obtainMessage(UPDATE_BUFFER);
                    _recordThread.mHandler.sendMessageDelayed(msg, 1000);
                    return;
                }

            }catch (Exception e){
                e.printStackTrace();
                _doStop();
                Message msg = _recordThread.mHandler.obtainMessage(UPDATE_BUFFER);
                _recordThread.mHandler.sendMessageDelayed(msg, 1000);
                return;
            }


            RecordData recordData = new RecordData();
            recordData.data = new byte[readSize];
            recordData.timeStamp = System.currentTimeMillis();
            System.arraycopy(_recordBuffer,0, recordData.data,0, readSize);
            if (_tempRecordBuffer.size() > 15){
                _tempRecordBuffer.poll();
            }
            _tempRecordBuffer.add(recordData);
        }
    }

    private void _mergeBuffer(RecordData recordData){
        int readSize = recordData.data.length;

        _lastBufferTimeStamp = recordData.timeStamp;
        if ((_lastPCMSize + readSize) >= _recordBuffer.length) {
            //trim last half pcm data
            int leftSize = _recordBuffer.length / 2 - readSize;
            System.arraycopy(_recordBuffer, _lastPCMSize - leftSize, _recordBuffer, 0, leftSize);
            _lastPCMSize = leftSize;
        }
        System.arraycopy(recordData.data,0,_recordBuffer,_lastPCMSize,readSize);
        _lastPCMSize += readSize;
    }

    private void _fetchBuffer(final RecordContext cxt){
        int expectReadSize = _lastRecordExpectSize;
        byte[] result = null;
        if (_recordStoped){
            _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB, null, RECORD_STOPED, new Exception("record use:" + _lastRecordSource + " record stop"), cxt.stopAfterReturn);
            return;
        }
        Object[] datas = _tempRecordBuffer.toArray();

        for (int index = 0;index < datas.length;++index){
            RecordData data = (RecordData)datas[index];
            if (data.timeStamp <= _lastBufferTimeStamp){
                continue;
            }
            _mergeBuffer(data);
        }

        if ((_hasExpired(cxt) && isRecording()) || (_lastPCMSize < expectReadSize)) {
            //if record buffer is out dated or not enough, we should wait or timeout
            if ((System.currentTimeMillis() - cxt.timeStamp) > cxt.timeOut) {
                _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB, null, RECORD_TIMEOUT, new Exception("record use:" + _lastRecordSource + " record time out"), cxt.stopAfterReturn);
                return;
            } else {
                Message msg = _notifyThread.mHandler.obtainMessage(FETCH_BUFFER, cxt);
                _notifyThread.mHandler.sendMessageDelayed(msg, DEFAULT_VALID_TIME_PERIOD / 5);
                return;
            }
        }

        if (!isRecording() && !_recordStoped) {
//            Log.d(TAG,"DEBUG2");
            _doRecord(-1, -1, cxt);
            return;
        }

//        Log.d(TAG, "Buffer time stamp: " + _lastBufferTimeStamp);
        result = new byte[expectReadSize];
        System.arraycopy(_recordBuffer, _lastPCMSize - expectReadSize, result, 0, expectReadSize);

        _processAndReturn(cxt, result);
    }

    private void _processAndReturn(RecordContext cxt, byte[] pcm){
        float dB = DEFAULT_LIMIT_DB;
        int expectReadSize = pcm.length;
        try {
            dB = getDB(pcm, expectReadSize, DEFAULT_RECORD_SAMPLE_RATE, RECORD_CHANNEL, RECORD_BITS, true);
        } catch (Exception e) {
            if (DEBUG){
                e.printStackTrace();
            }
            _safeRecordCallBack(cxt,dB,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ _lastRecordSource + " get dB fail"), true);
            return;
        }
        if (dB == THRESHOLD_DB){
            _safeRecordCallBack(cxt,dB,null,NO_RECORD_PERMISSION  ,  new Exception("record use:"+ _lastRecordSource + " get dB fail"), true);
            return;
        }else if (dB < cxt.limitDB){
            _safeRecordCallBack(cxt,dB,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ _lastRecordSource + " get dB fail"), true);
            return;
        }

        float pcmDB_start = 0;
        try {
            pcmDB_start = getDB(pcm, expectReadSize, DEFAULT_RECORD_SAMPLE_RATE, RECORD_CHANNEL, RECORD_BITS, false);
        } catch (Exception e) {
            if (DEBUG){
                e.printStackTrace();
            }
            _safeRecordCallBack(cxt,dB,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ _lastRecordSource + " get dB fail"), true);
            return;
        }
        if (pcmDB_start == THRESHOLD_DB){
            _safeRecordCallBack(cxt,pcmDB_start,null,NO_RECORD_PERMISSION  ,  new Exception("record use:"+ _lastRecordSource + " get dB fail"), true);
            return;
        }else if (pcmDB_start < cxt.limitDB){
            _safeRecordCallBack(cxt,pcmDB_start,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ _lastRecordSource + " get dB fail"), true);
            return;
        }

        byte[] binData = null;
        if (pcm != null){
            try {
                binData = buildBin(pcm, DEFAULT_RECORD_SAMPLE_RATE, _lastRecordPeriod,RECORD_CHANNEL,RECORD_BITS);
            } catch (Exception e) {
                if (DEBUG){
                    e.printStackTrace();
                }
                _safeRecordCallBack(cxt,(dB + pcmDB_start) / 2,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ _lastRecordSource + " get dB fail"), true);
                return;
            }
        }

        _safeRecordCallBack(cxt,(dB + pcmDB_start) / 2,binData,NO_ERROR, null, cxt.stopAfterReturn);

    }

    private void _doTestRecord(int source, int duration,final  RecordContext cxt){
        int audioSource = source;
        int recordPeriod = duration;

        int recordIndex = 0;
        if (audioSource < 0){
            recordIndex = _recordTestIndex;
            audioSource = _recordConfigs[recordIndex].src;
        }
        if (recordPeriod <= 0){
            recordPeriod = _recordConfigs[recordIndex].duration;
        }
        RecordConfig config = _recordConfigs[recordIndex];
        AudioRecord record = null;

        try{
            record = new AudioRecord(audioSource, _preferSampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT, 10 *1024);
            if (record.getState() != AudioRecord.STATE_INITIALIZED){
                _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
                _record(source, duration, cxt);
                return;
            }

        }catch (Exception e){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, cxt);
            return;
        }

        int realSampleRate = record.getSampleRate();
        if (realSampleRate != _preferSampleRate){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, cxt);
            return;
        }
        try{
            record.startRecording();
        }catch (Exception e){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, cxt);
            return;
        }

        if (record.getRecordingState() != RECORDSTATE_RECORDING){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, cxt);
            return;
        }

        long startTimeStamp = System.currentTimeMillis();

        int readsize = 0;
        int offset = 0;
        float lastDB = 0;
        float totalDB = 0;
        float loudestDB = -9999999;
        int loudestDBOffset = 0;
        int dBCount = 0;
        int validDBCount = 0;
        int READ_SIZE = TEST_FETCH_FRAMES * RECORD_CHANNEL * (RECORD_BITS / 8);

        while ((System.currentTimeMillis() - startTimeStamp) < recordPeriod){
            try{
                readsize = record.read(_recordBuffer,0, READ_SIZE);
                if (readsize <= 0){
                    offset = readsize;
                    break;
                }
                offset += readsize;
                lastDB = getDB(_recordBuffer, READ_SIZE, realSampleRate,RECORD_CHANNEL,RECORD_BITS,true);
                totalDB += lastDB;
                ++dBCount;
                if (lastDB > DEFAULT_LIMIT_DB){
                    if (lastDB > loudestDB){
                        loudestDB = lastDB;
                        loudestDBOffset = offset;
                    }
                    ++validDBCount;
                    if (validDBCount >= 3){
                        break;
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
                offset = -1;
                break;
            }
        }
        try {
            record.stop();
        }catch (Exception e){

        }
        try {
            record.release();
        }catch (Exception e){

        }
        if (offset < 0 || validDBCount <= 0){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
        }else{
            lastDB = totalDB / dBCount;
            _setTestResult(loudestDBOffset * 1000 / realSampleRate,lastDB > loudestDB? lastDB : loudestDB,false);
        }
        _record(source, duration, cxt);
    }

    private boolean _hasTestFinished(){
        if (_recordTestIndex == RECORD_CONFIG_COUNT)
            return true;

        return false;
    }

    private boolean _setTestResult(int delayTime, float power, boolean hasFailed){
        if (_hasTestFinished())
            return true;

        _recordConfigs[_recordTestIndex].delayTime = delayTime;
        _recordConfigs[_recordTestIndex].power = power;
        _recordConfigs[_recordTestIndex].duration = RECORD_PERIOD + ((delayTime * 100) / 100);

        if (power <= THRESHOLD_DB)
            hasFailed = true;

        if (hasFailed)
            ++_recordConfigs[_recordTestIndex].hasFailed;
        else
            _recordConfigs[_recordTestIndex].hasFailed = 0;

        ++_recordTestIndex;

        if (_hasTestFinished()){
            if (DEBUG){
                for (int index = 0;index < RECORD_CONFIG_COUNT;++index){
                    Log.d("audio rec", "test result: " + _recordConfigs[index].tag + " | " + _recordConfigs[index].power + " | " + _recordConfigs[index].delayTime);
                }
            }

            Arrays.sort(_sortedConfigs, _recordConfigComparator);
        }
        return _hasTestFinished();
    }


    public byte[] getDEBUGPCM(){
        if (_lastPCMData == null){
            return null;
        }
        int pcmDataSize = _lastPCMData.length / 2;
        int wavSize = pcmDataSize * 2 + 44;
        long totalAudioLen = pcmDataSize * 2;
        long totalDataLen = totalAudioLen + 36;
        long longSampleRate = _preferSampleRate;
        int channels = 1;
        long byteRate = 16 * longSampleRate * 1 / 8;

        byte[] header = new byte[wavSize];
        System.arraycopy(header, 44, _lastPCMData, 0, _lastPCMData.length);
        header[0] = 'R'; // RIFF
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff); //数据大小
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; //WAVE
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        //FMT Chunk
        header[12] = 'f'; // 'fmt '
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' '; //过渡字节
        //数据大小
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        //编码方式 10H为PCM编码格式
        header[20] = 1; // format = 1
        header[21] = 0;
        //通道数
        header[22] = (byte) channels;
        header[23] = 0;
        //采样率，每个通道的播放速度
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        //音频数据传送速率,采样率*通道数*采样深度/8
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        // 确定系统一次要处理多少个这样字节的数据，确定缓冲区，通道数*采样位数
        header[32] = (1 * 16 / 8);
        header[33] = 0;
        //每个样本的数据位数
        header[34] = 16;
        header[35] = 0;
        //Data chunk
        header[36] = 'd'; //data
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        return header;
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
        float average = 0;
        float longer = Math.abs(max)>Math.abs(min)?Math.abs(max):Math.abs(min);
        float factor = longer / 127;

        try {
            output.putFloat(average * 2);
            output.putFloat(factor * 2);

            for (int index = 0; index < numberOfSamples; ++index) {
                float temp = input[index] - average;
                int result = (int) (temp / factor);
                if (result > 127)
                    result = 127;
                else if (result < -127)
                    result = -127;
                output.put((byte)(result));
            }
            return numberOfSamples + 8;
        }catch (Exception e){
            e.printStackTrace();
            return 0;
        }
    }

    private String md5Decode32(byte[] content) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("MD5").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("NoSuchAlgorithmException",e);
        }
        //对生成的16字节数组进行补零操作
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            if ((b & 0xFF) < 0x10){
                hex.append("0");
            }
            hex.append(Integer.toHexString(b & 0xFF));
        }
        return hex.toString();
    }
}

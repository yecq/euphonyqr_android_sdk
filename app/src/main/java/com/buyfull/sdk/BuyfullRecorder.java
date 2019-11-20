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


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Comparator;


import static android.media.AudioRecord.RECORDSTATE_RECORDING;

public class BuyfullRecorder {
    public static final int NO_ERROR = 0;
    public static final int DUPLICATE_RECORD = 1;//重复录音
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
         * @param dB        此次录音的分贝数
         * @param bin       压缩后BIN数据
         * @param error     如果有错误则不为空
         */
        void onRecord(final float dB, final byte[] bin, final RecordException error);
    }

    private class RecordContext{
        public IRecordCallback      callback;
        public long                 timeStamp;
        public float                limitDB = DEFAULT_LIMIT_DB;
        public long                 timeOut = DEFAULT_RECORD_TIMEOUT;
        public long                 validTimePeriod = DEFAULT_VALID_TIME_PERIOD;
        public boolean              stopAfterReturn = false;//是否在录音返回后自动停止录音

        public RecordContext(JSONObject options, IRecordCallback cb){
            callback = cb;
            timeStamp = System.currentTimeMillis();
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
        RecordContext cxt = new RecordContext(options, callback);
        Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, -1, -1,cxt);
        msg.sendToTarget();
    }

    public void stop(){
        Message msg = _notifyThread.mHandler.obtainMessage(STOP_RECORD);
        msg.sendToTarget();
    }


    ////////////////////////////////////////////////////////////////////////
    private static final String     TAG = "BUYFULL_RECORDER";
    private static final boolean    DEBUG = true;

    private static final float Pi = 3.14159265358979f;
    private static final int N_WAVE = (64*1024);
    private static final int LOG2_N_WAVE = (6+10);
    private static final String SDK_VERSION = "1.0.1";

    private volatile static BuyfullRecorder instance;
    private static float                    fsin[];
    private float[]                         real;
    private float[]                         imag;
    private LooperThread                    _notifyThread;
    private byte[]                          _recordBuffer;
    private byte[]                          _tempRecordBuffer;
    private ByteBuffer                      _binBuffer;
    private AudioRecord                     _recorder;
    private long                            _lastBufferTimeStamp;

    private static final int START_RECORD = 1;
    private static final int STOP_RECORD = 2;
    private static final int START_TEST_RECORD = 3;
    private static final int FETCH_BUFFER = 4;

    private static class LooperThread extends Thread implements AudioRecord.OnRecordPositionUpdateListener{
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
                                }else{
                                    instance._doRecord(msg.arg1,msg.arg2,(RecordContext)msg.obj);
                                }
                            }
                            break;

                        case STOP_RECORD:
                            if (instance != null)
                                instance._doStop();
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
        public void onMarkerReached(AudioRecord audioRecord) {

        }

        @Override
        public void onPeriodicNotification(AudioRecord audioRecord) {
            if (instance != null) {
                instance._updateBuffer(audioRecord);
            }
        }
    }

    private BuyfullRecorder(){
        _tempRecordBuffer = new byte[RECORD_FETCH_FRAMES * 2 * RECORD_BITS / 8];
        _recordBuffer = new byte[230 * 1024];
        _binBuffer = ByteBuffer.allocate(8 * 1024).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void init(){
        Log.v(TAG,"Buyfull recorder version:" + SDK_VERSION);
        fsin = new float[N_WAVE];
        real = new float[N_WAVE];
        imag = new float[N_WAVE];

        _initRecordConfig();
        _notifyThread = new LooperThread("BuyfullRecorder");
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

        for (int i=0; i<N_WAVE; i++){
            fsin[i] = (float)Math.sin(2*Pi/N_WAVE*i);
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
        ByteBuffer pcmByte = ByteBuffer.wrap(pcmData, startIndex, pcmDataSize - startIndex).order(ByteOrder.LITTLE_ENDIAN);

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
        int stepCount = (sampleRate * recordPeriodInMS) / 1000;
        int stepSize = channels * (bits / 8);
        int resultSize = stepCount / 8;

        if (_binBuffer.capacity() < (resultSize + 12)){
            throw (new Exception("pcmData is too big"));
        }
        int startIndex = 0;
        if (!(sampleRate == DEFAULT_RECORD_SAMPLE_RATE)){
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
        ByteBuffer pcmByte = ByteBuffer.wrap(pcmData, startIndex, pcmDataSize - startIndex).order(ByteOrder.LITTLE_ENDIAN);

        float[] re = real;
        float[] im = imag;
        Arrays.fill(re,0);
        Arrays.fill(im,0);
        for (int index = 0;index < stepCount;++index,startIndex += stepSize){
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
        if (DEBUG)
            Log.d(TAG,"bin size " + finalSize);

        System.arraycopy(_binBuffer.array(),0,result,0,finalSize);
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
            }else if (power < THRESHOLD_DB){
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
    private int _lastPCMSize = 0;
    private String _lastRecordSource = "";
    private int _lastRecordPeriod = 0;
    private int _lastRecordExpectSize = 0;
    private long _lastRecordStartTime = -1;

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

    private void _record(int source, int duration, RecordContext cxt){
        if (_hasTestFinished()){
            Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, source, duration,cxt);
            msg.sendToTarget();
        }else{
            Message msg = _notifyThread.mHandler.obtainMessage(START_TEST_RECORD, source, duration,cxt);
            msg.sendToTarget();
        }
    }

    private void _safeRecordCallBack(RecordContext cxt, float dB, byte[] pcm, int errorCode, Exception error, boolean finish){
        try{
            if (finish){
                _doStop();
            }
            if (error != null){
                cxt.callback.onRecord(dB,null,new RecordException(errorCode, error));
            }else{
                cxt.callback.onRecord(dB,pcm,null);
            }

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
        if ((System.currentTimeMillis() - _lastBufferTimeStamp) > cxt.validTimePeriod){
            return true;
        }
        return false;
    }

    private void _doRecord(int source, int duration, RecordContext cxt){
        if (!_hasTestFinished()){
            _doTestRecord(source, duration, cxt);
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
                _recorder = new AudioRecord(audioSource, _preferSampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,16 * 1024);
                if (_recorder.getState() != AudioRecord.STATE_INITIALIZED){
                    _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL  ,new Exception("record use:"+ config.tag + " init failed1"), true);
                    return;
                }
            }catch (Exception e){
                _safeRecordCallBack(cxt, DEFAULT_LIMIT_DB,null,RECORD_FAIL  ,  new Exception("record use:"+ config.tag + " init failed1"), true);
                return;
            }

            _recorder.setPositionNotificationPeriod(RECORD_FETCH_FRAMES);
            _recorder.setRecordPositionUpdateListener(_notifyThread,_notifyThread.mHandler);
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
            int expectReadSize = (realSampleRate * recordPeriod * (RECORD_BITS / 8) )/ 1000;
            if ((expectReadSize % 2) == 1)
                --expectReadSize;
            _lastRecordExpectSize = expectReadSize;
        }
        Message msg = _notifyThread.mHandler.obtainMessage(FETCH_BUFFER, cxt);
        _notifyThread.mHandler.sendMessageDelayed(msg,DEFAULT_VALID_TIME_PERIOD);
    }

    private void _updateBuffer(AudioRecord record){
        final int expect_size = RECORD_FETCH_FRAMES * RECORD_BITS / 8;
        int readSize = 0;
        int offset = 0;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                do{
                    readSize = record.read(_tempRecordBuffer, offset, expect_size, AudioRecord.READ_NON_BLOCKING);
                    if (readSize < 0){
                        //error
                        _doStop();
                        return;
                    }
                    offset += readSize;
                    if ((readSize < expect_size) || ((offset + expect_size) > _tempRecordBuffer.length)){
                        break;
                    }
                }while (true);
            }else{
                readSize = record.read(_tempRecordBuffer, 0, expect_size);
                if (readSize < 0){
                    //error
                    _doStop();
                    return;
                }
                offset = readSize;
            }

            readSize = offset;
            if (DEBUG){
                Log.d(TAG,"update frames: " + readSize);
            }
        }catch (Exception e){
            e.printStackTrace();
            _doStop();
            return;
        }

        if ((_lastPCMSize + readSize) >= _recordBuffer.length){
            //trim last half pcm data
            int leftSize = _recordBuffer.length / 2 - readSize;
            if (DEBUG){
                Log.d(TAG,"update frames overflow, trim to left size: " + leftSize);
            }
            System.arraycopy(_recordBuffer,_lastPCMSize - leftSize,_recordBuffer,0,leftSize);
            _lastPCMSize = leftSize;
        }
        //copy temp buffer to record buffer
        System.arraycopy(_tempRecordBuffer,0,_recordBuffer,_lastPCMSize,readSize);
        _lastPCMSize += readSize;
        _lastBufferTimeStamp = System.currentTimeMillis();
    }

    private void _fetchBuffer(RecordContext cxt){
        int expectReadSize = _lastRecordExpectSize;
        if ((_hasExpired(cxt) && isRecording())|| (_lastPCMSize < expectReadSize)){
            //if record buffer is out dated or not enough, we should wait or timeout
            if ((System.currentTimeMillis() - cxt.timeStamp) > cxt.timeOut){
                _safeRecordCallBack(cxt,DEFAULT_LIMIT_DB,null,RECORD_TIMEOUT,new Exception("record use:"+ _lastRecordSource + " record time out"), true);
                return;
            }else{
                Message msg = _notifyThread.mHandler.obtainMessage(FETCH_BUFFER, cxt);
                _notifyThread.mHandler.sendMessageDelayed(msg,DEFAULT_VALID_TIME_PERIOD / 5);
                return;
            }
        }

        if (!isRecording()){
            _doRecord(-1,-1,cxt);
            return;
        }

        byte[] result = new byte[expectReadSize];
        System.arraycopy(_recordBuffer,_lastPCMSize - expectReadSize,result,0,expectReadSize);
        float dB = DEFAULT_LIMIT_DB;
        try {
            dB = getDB(result, expectReadSize, DEFAULT_RECORD_SAMPLE_RATE, RECORD_CHANNEL, RECORD_BITS, true);
        } catch (Exception e) {
            e.printStackTrace();
            _safeRecordCallBack(callback,dB,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ config.tag + " get dB fail"), false);
            return;
        }
        if (dB == THRESHOLD_DB){
            _safeRecordCallBack(callback,dB,null,NO_RECORD_PERMISSION  ,  new Exception("record use:"+ config.tag + " get dB fail"), true);
            return;
        }else if (dB < DEFAULT_LIMIT_DB){
            _safeRecordCallBack(callback,dB,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ config.tag + " get dB fail"), false);
            return;
        }
        _safeRecordCallBack(callback,dB,result,NO_ERROR, null, false);

        //检测分贝数，太低了说明很可能是没信号，后续不检测
        if (dB <= LIMIT_DB){
            if (DEBUG){
                Log.d(TAG, "pcm db is " + dB);
                Log.d(TAG,"Almost no signal, return");
            }

            _safeCallBack(callback,dB,null, null,true);
            return;
        }
        float pcmDB_start = 0;
        try {
            pcmDB_start = getDB(pcm, pcm.length, sampleRate, RECORD_CHANNEL,RECORD_BITS,false);
        } catch (Exception e) {
            _safeCallBack(callback,pcmDB_start,null, e,true);
        }
        //检测分贝数，太低了说明很可能是没信号，后续不检测
        if (pcmDB_start <= LIMIT_DB){
            if (DEBUG){
                Log.d(TAG, "pcm db is " + pcmDB_start);
                Log.d(TAG,"Almost no signal, return");
            }

            _safeCallBack(callback,pcmDB_start,null, null,true);
            return;
        }

        byte[] binData = null;
        try {
            binData = buildBin(pcm, sampleRate, recordPeriodInMS,RECORD_CHANNEL,RECORD_BITS);
        } catch (Exception e) {
            _safeCallBack(callback,dB,null, e,true);
        }

    }

    private void _doTestRecord(int source, int duration, RecordContext cxt){
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
            _setTestResult(loudestDBOffset * 1000 / realSampleRate,lastDB,false);
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

}

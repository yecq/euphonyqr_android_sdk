package com.buyfull.sdk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
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

    public static final float      LIMIT_DB = -125f; //分贝阈值，低于此值不上传判断

    public class RecordException extends Exception{
        public int code;
        public RecordException(int _code, Exception error) {
            super(error);
            code = _code;
        }
        public RecordException(int _code, String message) {
            super(message);
            code = _code;
        }
    }
    private static final String     TAG = "BUYFULL_RECORDER";
    private static final boolean    DEBUG = true;

    public interface IRecordCallback {
        /**
         * 录音结束后回调
         * @param dB        此次录音的分贝数
         * @param pcm       纯PCM数据
         * @param error     如果有错误则不为空
         */
        void onRecord(final float dB, final byte[] pcm, final RecordException error);
    }

    public synchronized static BuyfullRecorder getInstance(){
        if (instance == null){
            instance = new BuyfullRecorder();
            instance.init();
        }
        return instance;
    }
    /**
     * 当前是否正在录音检测，不能重复调用detect
     * @return
     */
    public synchronized boolean isRecording(){
        return _isRecording;
    }
    /**
     *  录音并且返回纯pcm数据，默认录音参数为48000,16bit,单声道，时长1.1秒。
     * @param options
     * @param callback
     */
    public void record(JSONObject options, IRecordCallback callback){
        if (callback == null)   return;
        if (isRecording()){
            callback.onRecord(LIMIT_DB,null,new RecordException(DUPLICATE_RECORD, "please retry record later"));
            return;
        }
        Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, -1, -1,callback);
        msg.sendToTarget();
    }

    public void stop(){
        _isRecording = false;
    }


    ////////////////////////////////////////////////////////////////////////
    private double toDB(double amp){
        if (amp <= 0 || Double.isNaN(amp) || Double.isInfinite(amp))
            return THRESHOLD_DB;

        return Math.log(amp) * (8.6858896380650365530225783783322);
    }

    private float getDB(byte[] pcmData, int pcmDataSize, int sampleRate, int channels, int bits, boolean isLastFrame) throws Exception{
        if (pcmData == null){
            throw (new Exception("invalid pcmData or outBin:"));
        }
        int stepCount = 2048;
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
        if (sampleRate == 48000){
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
        if (sampleRate == 48000){
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
    private static final float Pi = 3.14159265358979f;
    private static final int N_WAVE = (64*1024);
    private static final int LOG2_N_WAVE = (6+10);
    private static final String SDK_VERSION = "1.0.1";

    private volatile static BuyfullRecorder      instance;
    private static float                    fsin[];
    private float[]                         real;
    private float[]                         imag;
    private LooperThread                    _notifyThread;
    private volatile boolean                _threadStarted;
    private byte[]                          _recordBuffer;
    private ByteBuffer                      _binBuffer;
    private volatile boolean _isRecording;


    private static final int START_RECORD = 1;
    private static final int STOP_RECORD = 2;
    private static final int START_TEST_RECORD = 3;

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
                        case START_RECORD:

                            if (instance != null){
                                if (!instance._hasTestFinished()){
                                    instance._doTestRecord(msg.arg1,msg.arg2,(IRecordCallback)msg.obj);
                                }else{
                                    instance._doRecord(msg.arg1,msg.arg2,(IRecordCallback)msg.obj);
                                }
                            }
                            break;

                        case STOP_RECORD:
                            if (instance != null)
                                instance._doStop();
                            break;

                        case START_TEST_RECORD:
                            if (instance != null) {
                                instance._doTestRecord(msg.arg1,msg.arg2,(IRecordCallback)msg.obj);
                            }
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

    private BuyfullRecorder(){
        _recordBuffer = new byte[200 * 1024];
        _binBuffer = ByteBuffer.allocate(8 * 1024).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void init(){
        fsin = new float[N_WAVE];
        real = new float[N_WAVE];
        imag = new float[N_WAVE];

        for (int i=0; i<N_WAVE; i++){
            fsin[i] = (float)Math.sin(2*Pi/N_WAVE*i);
        }

        _initRecordConfig();
        _notifyThread = new LooperThread("BuyfullRecorder");
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

    private static final float THRESHOLD_DB = -150;
    private static final int THRESHOLD_DELAY = 1000;

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
    private int _preferSampleRate = 48000;
    private int _lastPCMSize = 0;
    private String _lastRecordSource = "";
    private int _lastRecordPeriod = 0;

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

    private void _record(int source, int duration, IRecordCallback callback){
        if (callback == null)   return;
        if (_hasTestFinished()){
            Message msg = _notifyThread.mHandler.obtainMessage(START_RECORD, source, duration,callback);
            msg.sendToTarget();
        }else{
            Message msg = _notifyThread.mHandler.obtainMessage(START_TEST_RECORD, source, duration,callback);
            msg.sendToTarget();
        }
    }

    private void _safeCallBack(IRecordCallback callback, float dB, byte[] pcm, int errCode, String exception, boolean finish){
        try{
            if (finish){
                _isRecording = false;
            }
            if (pcm != null){
                callback.onRecord(dB,pcm,null);
            }else{
                callback.onRecord(dB,pcm,new RecordException(errCode, exception));
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    private void _safeRecordCallBack(IRecordCallback callback, float dB, byte[] pcm, int errorCode, Exception error, boolean finish){
        try{
            if (finish){
                _doStop();
            }
            if (error != null){
                callback.onRecord(dB,null,new RecordException(errorCode, error));
            }else{
                callback.onRecord(dB,pcm,null);
            }

        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void _doStop(){
        //TODO::
        try{

        }catch (Exception e){

        }
    }

    private void _doRecord(int source, int duration, IRecordCallback callback){
        if (!_hasTestFinished()){
            _doTestRecord(source, duration, callback);
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
            _safeRecordCallBack(callback,LIMIT_DB,null,RECORD_FAIL  , new Exception("record use:"+ config.tag + " is not available"), true);
            return;
        }

        AudioRecord record = null;
        try{
            record = new AudioRecord(audioSource, _preferSampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,16 * 1024);
            if (record.getState() != AudioRecord.STATE_INITIALIZED){
                _safeRecordCallBack(callback,LIMIT_DB,null,RECORD_FAIL  ,new Exception("record use:"+ config.tag + " init failed1"), true);
                return;
            }
        }catch (Exception e){
            _safeRecordCallBack(callback,LIMIT_DB,null,RECORD_FAIL  ,  new Exception("record use:"+ config.tag + " init failed1"), true);
            return;
        }


        int realSampleRate = record.getSampleRate();
        if (realSampleRate != _preferSampleRate){
            _safeRecordCallBack(callback,LIMIT_DB,null,RECORD_FAIL  ,  new Exception("record use:"+ config.tag + " init failed2"), true);
            return;
        }
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
            _safeRecordCallBack(callback,LIMIT_DB,null,RECORD_FAIL,e, true);
            return;
        }

        if (record.getRecordingState() != RECORDSTATE_RECORDING){
            _safeRecordCallBack(callback,LIMIT_DB,null,RECORD_FAIL  ,  new Exception("record use:"+ config.tag + " start record fail"), true);
            return;
        }

        long startTimeStamp = System.currentTimeMillis();
        int expectReadSize = (realSampleRate * recordPeriod * (RECORD_BITS / 8) )/ 1000;
        if ((expectReadSize % 2) == 1)
            --expectReadSize;

        _lastPCMSize = expectReadSize;
        _lastRecordSource = config.tag;
        _lastRecordPeriod = recordPeriod;

        int readsize = 0;
        int offset = 0;
        do{
            try{
                readsize = record.read(_recordBuffer, offset,expectReadSize - offset);
                if (readsize <= 0){
                    break;
                }
                offset += readsize;
            }catch (Exception e){
                e.printStackTrace();
                readsize = -1;
            }
        }while (offset < expectReadSize);

        try {
            record.stop();
        }catch (Exception e){

        }
        try {
            record.release();
        }catch (Exception e){

        }
        long passedTime = System.currentTimeMillis() - startTimeStamp;
        if (offset < expectReadSize || (passedTime < recordPeriod) || (passedTime > (recordPeriod + 500))){
            _safeRecordCallBack(callback,LIMIT_DB,null,RECORD_TIMEOUT  ,  new Exception("record use:"+ config.tag + " record fail"), false);
        }else{
            byte[] result = new byte[expectReadSize];
            System.arraycopy(_recordBuffer,0,result,0,expectReadSize);
            float dB = LIMIT_DB;
            try {
                dB = getDB(result, expectReadSize, realSampleRate, RECORD_CHANNEL, RECORD_BITS, true);
            } catch (Exception e) {
                e.printStackTrace();
                _safeRecordCallBack(callback,dB,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ config.tag + " get dB fail"), false);
                return;
            }
            if (dB == THRESHOLD_DB){
                _safeRecordCallBack(callback,dB,null,NO_RECORD_PERMISSION  ,  new Exception("record use:"+ config.tag + " get dB fail"), true);
                return;
            }else if (dB < LIMIT_DB){
                _safeRecordCallBack(callback,dB,null,SIGNAL_DB_TOO_LOW  ,  new Exception("record use:"+ config.tag + " get dB fail"), false);
                return;
            }
            _safeRecordCallBack(callback,dB,result,NO_ERROR, null, false);
        }
    }

    private void _doTestRecord(int source, int duration, IRecordCallback callback){
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
                _record(source, duration, callback);
                return;
            }
        }catch (Exception e){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, callback);
            return;
        }


        int realSampleRate = record.getSampleRate();
        if (realSampleRate != _preferSampleRate){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, callback);
            return;
        }
        try{
            record.startRecording();
        }catch (Exception e){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, callback);
            return;
        }

        if (record.getRecordingState() != RECORDSTATE_RECORDING){
            _setTestResult(THRESHOLD_DELAY,THRESHOLD_DB,true);
            _record(source, duration, callback);
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
        int READ_SIZE = 2048 * RECORD_CHANNEL * (RECORD_BITS / 8);

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
                if (lastDB > LIMIT_DB){
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
        _record(source, duration, callback);
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

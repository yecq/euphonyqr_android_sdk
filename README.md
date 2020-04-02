# euphonyqr_android_sdk

动听官网 http://www.euphonyqr.com</br>
动听测试服 http://sandbox.euphonyqr.com</br>

1. 准备</br>
  请和动听工作人员联系获取售前服务文档，并全部完成。如果只是想尝试一下SDK，可以跳过这一步。
2. 集成SDK</br>
  用AndroidStudio打开示例工程，编译运行，SDK代码是BuyfullRecorder.java，示例代码在MyApplication.java和MainActivity.java中, PermissionPageUtils.java是帮助打开权限设置页面的，仅供参考：</br>
  BuyfullSDK.java是辅助代码，帮助用户把整个检测流程整合，可自行修改，其中的detectRequest（51行)为示例布署代码，请自行根据业务逻辑修改</br>
  BuyfullSDK.java中的检测流程代码在 private void _detect 和 private void onRecord 中。</br>
  参照示例代码，大体业务流程是：</br></br>
  1）初始化：参考MyApplication.java,在onCreate中初始化sdk，需要context，同时传入的参数有</br>
  (a) appkey | string | 注册了动听帐号后可以在个人中心->应用管理中查看appkey</br>
  (b) tokenURL | string | 请自行布署一个后端服务器用来获取token，访问动听api需要token， 具体请见 https://github.com/haoboyang/qs_wx_token</br>
  (c) detectURL | string | 请自行布署一个后端服务器用来获取检测结果，访问动听api需要appkey和seckey， 具体请见 https://github.com/haoboyang/qs_wx_token</br></br>
  2）授权：检测并引导用户授权，期间需要多次调用BuyfullSDK.getInstance().setContext()，本SDK不保存Context，不会内存泄露。</br></br>
  3）检测：参考MainActivity.java中的doDetect方法，调用detect，等待返回结果。如果想要反复检测，可以在检测回调后立即在主线程再次调用detect。可选的参数有customData(string类型，可以通过动听后台API加上requestID查询返回)</br></br>
  4 ) 处理返回结果：MainActivity.java第103行 </br>
    一共4个返回参数: (final JSONObject options, final float dB,final String result,final Exception error)</br>
    (a) options是检测时传入的参数
    (b) dB表示录音的分贝数，一般 -90以上信号质量较好，-120及以下基本为无信号</br>
    (c) result为返回数据，根据各自的业务逻辑不同而异</br>
    (d) error为出错说明信息，没有错误时为null</br>

3. 测试</br>
  从动听工作人员处取得测试音频或测试设备，测试音频请用mac电脑（IBM，联想，三星电脑不行）或专业音响，蓝牙音响播放，测试设备使用方法请咨询动听工作人员。</br></br>
4. 注意事项和常见问题：</br>
  1）初始化请尽可能的提前，BuyfullSDK为单例组件</br>
  2）请分清楚APPKEY和SECKEY是在动听官网 http://www.euphonyqr.com 申请的还是在动听测试服 http://sandbox.euphonyqr.com 申请的。线下店帐号和APP帐号都要在同一平台上申请才能互相操作。</br>
  3）detect回调不在主线程，如果要更新UI请在主线程操作。</br>
  4）请确保网络通畅并且可以连接外网。</br>
  5）开发人员需要自行申请麦克风权限，同时建议在APPSTORE提交审合时动态暂时关闭检测功能以免解释麻烦。</br>
  6 ) 请查看一下MyApplication.java和MainActivity.java中的注释。</br>
  7 ) 请至少在APP帐号下购买一个渠道后再进行测试，并且请在渠道中自行设定，自行设定，自行设定（重要的事情说三遍）识别结果，可以为任何字符串包括JSON。</br>
  8 ) 主体逻辑部分都在detect方法中，可以自行看代码并修改业务流程。</br>
  9 ) 需要的权限为 READ_PHONE_STATE和 RECORD_AUDIO</br>
5. API说明</br>
  请查看一下buyfullSDK.java中的方法注释，带注释的为公开方法
  
  
  有疑问请联系QQ:55489181
package kr.co.edoubles.awsiotmqtt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.amazonaws.mobileconnectors.iot.AWSIotKeystoreHelper;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttSubscriptionStatusCallback;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Scanner;

public class AWSIotMqtt {
    private static final String TAG = "AWSIotMqtt";

    private final Context context;
    private final AWSIotMqttManager mqttManager;
    private final OnMqttCallback mqttCallback;

    private KeyStore clientKeyStore;

    private final String clientId;
    private final String endpoint;
    private final String certificateId;
    private final String keystoreName;
    private final String keystorePassword;

    public AWSIotMqtt(Context context, OnMqttCallback mqttCallback) {
        this.context = context;
        this.mqttCallback = mqttCallback;

        this.clientId = context.getString(R.string.client_id);
        this.endpoint = context.getString(R.string.CUSTOMER_SPECIFIC_ENDPOINT);
        this.certificateId = context.getString(R.string.CERTIFICATE_ID);
        this.keystoreName = context.getString(R.string.KEYSTORE_NAME);
        this.keystorePassword = context.getString(R.string.KEYSTORE_PASSWORD);

        mqttManager = new AWSIotMqttManager(clientId, endpoint);
    }

    public void prepareKeyStore() throws Exception {
        InputStream certInputStream = null;
        InputStream privateKeyInputStream = null;
        try {
            certInputStream = context.getResources().openRawResource(R.raw.device_cert);
            privateKeyInputStream = context.getResources().openRawResource(R.raw.private_key);

            String certPem = convertStreamToString(certInputStream);
            String privateKeyPem = convertStreamToString(privateKeyInputStream);

            String keystorePath = context.getFilesDir().getPath();

            if (!AWSIotKeystoreHelper.isKeystorePresent(keystorePath, keystoreName)) {
                // 키스토어 생성
                AWSIotKeystoreHelper.saveCertificateAndPrivateKey(
                        certificateId,
                        certPem,
                        privateKeyPem,
                        keystorePath,
                        keystoreName,
                        keystorePassword
                );
                Log.d(TAG, "AWSIotKeystore 생성 완료");
            }

            // 키스토어 로드
            clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(
                    certificateId,
                    keystorePath,
                    keystoreName,
                    keystorePassword
            );
            Log.d(TAG, "AWSIotKeystore 로드 완료");

        } finally {
            // InputStream을 닫아 리소스 누수를 방지.
            if (certInputStream != null) {
                try {
                    certInputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "인증서 InputStream 닫기 오류 : ", e);
                }
            }
            if (privateKeyInputStream != null) {
                try {
                    privateKeyInputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "개인 키 InputStream 닫기 오류 : ", e);
                }
            }
        }
    }

    private String convertStreamToString(InputStream is) throws Exception {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        String result = s.hasNext() ? s.next() : "";
        s.close();
        return result;
    }

    public void connect() {
        mqttManager.connect(clientKeyStore, new AWSIotMqttClientStatusCallback() {
            @Override
            public void onStatusChanged(AWSIotMqttClientStatus status, Throwable throwable) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, status.toString());

                        if(status == AWSIotMqttClientStatus.Connected){
                            Log.d(TAG, "----- AWS IoT MQTT 연결됨 -----");
                            mqttCallback.connected();
                        }
                    }
                });
            }
        });
    }

    public void disconnect() {
        mqttManager.disconnect();
        Log.d(TAG, "MQTT 연결 해제");
    }

    public void subscribe(String topic) {
        try {
            mqttManager.subscribeToTopic(topic, AWSIotMqttQos.QOS0, new AWSIotMqttSubscriptionStatusCallback() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Subscribe 성공 : " + topic);
                    mqttCallback.onSubscribeSuccess(topic);
                }

                @Override
                public void onFailure(Throwable exception) {
                    Log.e(TAG, "Subscribe 실패 : " + topic, exception);

                }
            }, new AWSIotMqttNewMessageCallback() {
                @Override
                public void onMessageArrived(String topic, byte[] data) {
                    mqttCallback.messageArrived(topic, data);
                }
            });
        } catch (Exception e){
            Log.e(TAG, "구독 중 오류 발생 : " + topic, e);
        }
    }

    public void publish(String topic, String message) {
        try {
            mqttManager.publishString(message, topic, AWSIotMqttQos.QOS0);
            Log.d(TAG, "Publish 성공 : " + topic);
        } catch (Exception e){
            Log.e(TAG, "Publish 중 오류 발생 : " + topic, e);
        }
    }
}

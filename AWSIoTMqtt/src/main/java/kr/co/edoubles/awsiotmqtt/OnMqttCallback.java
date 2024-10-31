package kr.co.edoubles.awsiotmqtt;

public interface OnMqttCallback {
    void connected();
    void onSubscribeSuccess(String topic);
    void messageArrived(String topic, byte[] data);
}

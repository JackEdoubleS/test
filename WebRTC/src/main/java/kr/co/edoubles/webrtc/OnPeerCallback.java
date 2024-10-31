package kr.co.edoubles.webrtc;

import org.webrtc.IceCandidate;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;

public interface OnPeerCallback {
    void onIceCandidate(String peerName, IceCandidate iceCandidate);
    void onOfferCreated(String peerName, SessionDescription sessionDescription);
    void onAnswerCreated(String peerName, SessionDescription sessionDescription);

    void onMessage(String receivedMessage);
    void onMessage(String receivedMessage, String peerName);
    void onTrack(RtpTransceiver transceiver, String peerName);
}

package kr.co.edoubles.webrtc;

import android.content.Context;
import android.util.Log;

import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;

public class PeerManager {
    private static final String TAG = "PeerManager";

    private final Context context;

    private final EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private HashMap<String, PeerConnection> peerConnections = new HashMap<>();
    private HashMap<String, DataChannel> dataChannels = new HashMap<>();

    private final OnPeerCallback onPeerCallback;

    public PeerManager(Context context, EglBase eglBase, OnPeerCallback onPeerCallback) {
        this.context = context;
        this.eglBase = eglBase;
        this.onPeerCallback = onPeerCallback;

        createPeerConnectionFactory();
    }

    public void createPeerConnectionFactory() {
        PeerConnectionFactory.InitializationOptions initializationOptions = PeerConnectionFactory
                .InitializationOptions.builder(context)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(),  /* enableIntelVp8Encoder */true,  /* enableH264HighProfile */true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder().setVideoEncoderFactory(defaultVideoEncoderFactory).setVideoDecoderFactory(defaultVideoDecoderFactory).createPeerConnectionFactory();

        Log.d(TAG, "PeerConnectionFactory 생성됨");
    }

    public PeerConnectionFactory getPeerConnectionFactory(){
        return peerConnectionFactory;
    }

    public void createPeerConnection(String peerName) {
        if (peerConnections.containsKey(peerName)) {
            deletePeerConnection(peerName);
        }

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.singletonList(new PeerConnection.IceServer("stun:stun.l.google.com:19302")));
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(config, new CustomPeerConnectionObserver(peerName));

        peerConnections.put(peerName, peerConnection);
        Log.d(TAG, "PeerConnection 생성됨: " + peerName);
    }

    private class CustomPeerConnectionObserver implements PeerConnection.Observer {
        private String peerName;

        public CustomPeerConnectionObserver(String peerName){
            this.peerName = peerName;
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "onSignalingChanged : " + signalingState.toString());
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "onIceConnectionChange : " + iceConnectionState.toString());
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
            Log.d(TAG, "onIceConnectionReceivingChange : " + b);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "onIceGahteringChange : " + iceGatheringState.toString());
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.d(TAG, "IceCandidate 생성됨 : " + iceCandidate);
            onPeerCallback.onIceCandidate(peerName, iceCandidate);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            Log.d(TAG, "onIceCandidatesRemoved");
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            // 사용되지 않음
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            // 사용되지 않음
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(TAG, "Data Channel 수신함");

            dataChannel.registerObserver(new DataChannel.Observer() {
                @Override
                public void onBufferedAmountChange(long l) {
                    // 필요 시 구현
                }

                @Override
                public void onStateChange() {
                    // 필요 시 구현
                }

                @Override
                public void onMessage(DataChannel.Buffer buffer) {
                    ByteBuffer data = buffer.data;
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    String receivedMessage = new String(bytes, StandardCharsets.UTF_8);
                    onPeerCallback.onMessage(receivedMessage, peerName);
                }
            });

            if (dataChannels.containsKey(peerName)) {
                deleteDataChannel(peerName);
            }
            dataChannels.put(peerName, dataChannel);
        }

        @Override
        public void onRenegotiationNeeded() {
            // 필요 시 구현
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            // 사용되지 않음
        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            PeerConnection.Observer.super.onTrack(transceiver);
            onPeerCallback.onTrack(transceiver, peerName);
        }
    }

    public PeerConnection getPeerConnection(String peerName) {
        return peerConnections.get(peerName);
    }

    public void deletePeerConnection(String peerName) {
        PeerConnection peerConnection = peerConnections.remove(peerName);
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            Log.d(TAG, "PeerConnection 삭제됨: " + peerName);
        }
    }

    public void deleteAllPeers() {
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
        }

        for (PeerConnection peerConnection : peerConnections.values()) {
            if (peerConnection != null) {
                peerConnection.close();
                peerConnection.dispose();
            }
        }
        peerConnections.clear();
        eglBase.release();
        Log.d(TAG, "모든 PeerConnection 삭제됨");
    }

    public void createDataChannel(String peerName) {
        if (!peerConnections.containsKey(peerName)) {
            Log.e(TAG, "DataChannel 생성 불가; PeerConnection이 존재하지 않음: " + peerName);
            return;
        }

        DataChannel.Init dcInit = new DataChannel.Init();
        dcInit.ordered = true;

        DataChannel dataChannel;

        try {
            dataChannel = peerConnections.get(peerName).createDataChannel(peerName, dcInit);
        } catch (Exception e){
            Log.e(TAG, "Data Channel 생성 실패", e);
            return;
        }

        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {
                // 필요 시 구현
            }

            @Override
            public void onStateChange() {
                // 필요 시 구현
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                String receivedMessage = new String(bytes, StandardCharsets.UTF_8);
                onPeerCallback.onMessage(receivedMessage);
            }
        });

        deleteDataChannel(peerName);
        dataChannels.putIfAbsent(peerName, dataChannel);

        Log.d(TAG, "Data Channel 생성 성공");
    }

    public void sendData(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);

        for (String peerName : dataChannels.keySet()) {
            DataChannel dataChannel = dataChannels.get(peerName);
            if (dataChannel.state() == DataChannel.State.OPEN) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                DataChannel.Buffer dataBuffer = new DataChannel.Buffer(buffer, false);
                dataChannel.send(dataBuffer);
                Log.d(TAG, "데이터 전송됨: " + data + " -> " + peerName);
            }
        }
    }


    public void deleteDataChannel(String peerName){
        DataChannel dataChannel = dataChannels.remove(peerName);
        if (dataChannel != null) {
            dataChannel.close();
            dataChannel.dispose();
            Log.d(TAG, "DataChannel 삭제됨: " + peerName);
        }
    }

    public void deleteAllDataChannels() {
        for (DataChannel dataChannel : dataChannels.values()) {
            if (dataChannel != null) {
                dataChannel.close();
                dataChannel.dispose();
            }
        }
        dataChannels.clear();
        Log.d(TAG, "모든 DataChannel 삭제됨");
    }

    public void addTrack(String peerName, MediaStreamTrack track){
        PeerConnection peerConnection = peerConnections.get(peerName);
        if (peerConnection != null) {
            peerConnection.addTrack(track);
            Log.d(TAG, "트랙 추가됨: " + peerName);
        }
    }

    public void createOffer(String peerName) {
        PeerConnection peerConnection = peerConnections.get(peerName);
        if (peerConnection == null) {
            Log.e(TAG, "Offer 생성 불가; PeerConnection이 존재하지 않음: " + peerName);
            return;
        }

        peerConnection.createOffer(new CustomSdpObserver("createOffer") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new CustomSdpObserver("setLocalDescription") {
                    @Override
                    public void onSetSuccess() {
                        super.onSetSuccess();
                        Log.d(TAG, "setLocalDescription 성공");
                        onPeerCallback.onOfferCreated(peerName, sessionDescription);
                    }
                }, sessionDescription);
            }
        }, new MediaConstraints());
    }

    public void createAnswer(String peerName) {
        PeerConnection peerConnection = peerConnections.get(peerName);
        if (peerConnection == null) {
            Log.e(TAG, "Answer 생성 불가; PeerConnection이 존재하지 않음: " + peerName);
            return;
        }

        peerConnection.createAnswer(new CustomSdpObserver("createAnswer") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new CustomSdpObserver("setLocalDescription") {
                    @Override
                    public void onSetSuccess() {
                        super.onSetSuccess();
                        Log.d(TAG, "setLocalDescription 성공");
                        onPeerCallback.onAnswerCreated(peerName, sessionDescription);
                    }
                }, sessionDescription);
            }
        }, new MediaConstraints());
    }

    public void setRemoteDescription(String peerName, String sdp, SessionDescription.Type type) {
        PeerConnection peerConnection = peerConnections.get(peerName);
        if (peerConnection == null) {
            Log.e(TAG, "setRemoteDescription 불가; PeerConnection이 존재하지 않음: " + peerName);
            return;
        }

        SessionDescription remoteSdp = new SessionDescription(type, sdp);

        peerConnection.setRemoteDescription(new CustomSdpObserver("setRemoteDescription") {
            @Override
            public void onSetSuccess() {
                super.onSetSuccess();
                Log.d(TAG, "setRemoteDescription 성공");
                if (type == SessionDescription.Type.OFFER) {
                    createAnswer(peerName);
                }
            }
        }, remoteSdp);
    }

    public void addRemoteCandidate(String peerName, String candidateString) {
        IceCandidate candidate = new IceCandidate("0", 0, candidateString);
        PeerConnection peerConnection;
        if(peerConnections.containsKey(peerName)){
            peerConnection = peerConnections.get(peerName);
        } else {
            return;
        }

        try{
            peerConnection.addIceCandidate(candidate);
            Log.d(TAG, "SUCCESS addRemoteCandidate : " + candidateString);
        } catch (Exception e){
            Log.e(TAG, "FAILED addRemoteCandidate : " + candidateString);
        }
    }

    private abstract class CustomSdpObserver implements SdpObserver {

        private final String tag;

        CustomSdpObserver(String tag){
            this.tag = tag;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {

        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, tag + " onCreateFailure: " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, tag + " onSetFailure: " + s);
        }
    }
}
package kr.co.edoubles.carlostdetect.service;

import org.tensorflow.lite.task.vision.detector.Detection;
import org.webrtc.VideoTrack;

import java.util.List;

public interface ServiceListener {
    void onVideoTrackReceived(VideoTrack videoTrack);
    void setLostString(String lost);
    void drawCustomBox(List<Detection> results, int imageHeight, int imageWidth);
    void invalidateCustomBox();
}

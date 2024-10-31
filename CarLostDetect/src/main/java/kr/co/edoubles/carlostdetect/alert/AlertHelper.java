package kr.co.edoubles.carlostdetect.alert;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

public class AlertHelper {

    private ToneGenerator toneGenerator;
//    private TextToSpeech textToSpeech;


    public AlertHelper(Context context) {
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        /*textToSpeech=new TextToSpeech(context, status -> {
            if (status != TextToSpeech.ERROR) {
                textToSpeech.setLanguage(Locale.KOREA);
            }
        });*/
    }

    public void playTone(String lost) {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 500);
//        textToSpeech.speak(lost,TextToSpeech.QUEUE_FLUSH,null, "TTS");

    }

    public void release() {
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }

    }
}

package eu.devsrv.samapp.desktop;

import java.io.BufferedInputStream;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

/** Crowd reactions: 2s clips (cheer.wav, boo.wav in resources) with a 0.5s fade-out baked in. */
final class Sounds {
    static void cheer() { play("cheer"); }

    static void boo() { play("boo"); }

    private static void play(String name) {
        Store.log("sound " + name + ": requested");
        try {
            Clip c = AudioSystem.getClip();
            c.open(AudioSystem.getAudioInputStream(new BufferedInputStream(Sounds.class.getResourceAsStream("/" + name + ".wav"))));
            c.addLineListener(e -> {
                if (e.getType() == LineEvent.Type.STOP) { c.close(); Store.log("sound " + name + ": finished"); }
            });
            c.start();
            Store.log("sound " + name + ": playing");
        } catch (Exception e) {
            Store.log("sound " + name + ": failed " + e);
        }
    }
}

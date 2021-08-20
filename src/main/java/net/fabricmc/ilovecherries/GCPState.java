package net.fabricmc.ilovecherries;

import java.util.Vector;

// If REI is allowed to make a singleton in order to contain state, I don't see why I shouldn't!
public class GCPState {
    private static Vector<String> downloaded;
    private static Vector<String> deleted;
    private static Vector<String> updated;

    public static Vector<String> getDownloaded() {
        return downloaded;
    }

    public static void addDownloaded(String entry) {
        downloaded.add(entry);
    }

    public static Vector<String> getDeleted() {
        return deleted;
    }

    public static void addDeleted(String entry) {
        deleted.add(entry);
    }

    public static Vector<String> getUpdated() {
        return updated;
    }

    public static void addUpdated(String entry) {
        updated.add(entry);
    }

    public static boolean anyChange() {
        return (!downloaded.isEmpty()
                || !deleted.isEmpty()
                || !updated.isEmpty());
    }
}

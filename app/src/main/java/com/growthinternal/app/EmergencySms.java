package com.growthinternal.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SOS fallback over the carrier network: works without data quota as long as a cell tower is up.
 * The message is handed to the phone's SMS app, so no SMS permission is needed.
 */
final class EmergencySms {
    static final int MAX_CONTACTS = 3;

    private EmergencySms() {}

    /** Splits user input on commas/semicolons/newlines and keeps up to MAX_CONTACTS plausible numbers. */
    static List<String> parseNumbers(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String part : raw.split("[,;\\n]")) {
            StringBuilder n = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if ((c >= '0' && c <= '9') || (c == '+' && n.length() == 0)) n.append(c);
            }
            int digits = n.length() - (n.length() > 0 && n.charAt(0) == '+' ? 1 : 0);
            if (digits < 5 || digits > 15 || out.contains(n.toString())) continue;
            out.add(n.toString());
            if (out.size() == MAX_CONTACTS) break;
        }
        return out;
    }

    static String join(List<String> numbers, String sep) {
        StringBuilder b = new StringBuilder();
        for (String n : numbers) {
            if (b.length() > 0) b.append(sep);
            b.append(n);
        }
        return b.toString();
    }

    /**
     * Short enough for 2 SMS parts. fixAgeMinutes < 0 means the fix is fresh or unknown.
     */
    static String body(String name, boolean hasLocation, double lat, double lon, float accuracy,
                       long fixAgeMinutes, int battery) {
        StringBuilder b = new StringBuilder();
        b.append("SOS dari ").append(name).append(" (Growth Internal). Saya butuh bantuan.");
        if (hasLocation) {
            b.append(String.format(Locale.US, " Lokasi: https://maps.google.com/?q=%.6f,%.6f", lat, lon));
            List<String> notes = new ArrayList<String>();
            if (accuracy > 0) notes.add("akurasi " + Math.round(accuracy) + " m");
            if (fixAgeMinutes >= 1) notes.add(fixAgeMinutes + " menit lalu");
            if (!notes.isEmpty()) b.append(" (").append(join(notes, ", ")).append(')');
            b.append('.');
        } else {
            b.append(" Lokasi GPS belum tersedia.");
        }
        if (battery >= 0) b.append(" Baterai ").append(battery).append("%.");
        return b.toString();
    }
}

package com.salingjaga.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class EmergencySmsTest {
    @Test public void parsesUpToThreeCleanNumbers() {
        assertEquals(Arrays.asList("081234567890", "+6281311112222", "02150001234"),
                EmergencySms.parseNumbers(" 0812-3456-7890, +62 813 1111 2222;(021) 5000 1234\n0899999999"));
    }

    @Test public void dropsJunkAndDuplicates() {
        assertEquals(Arrays.asList("081234567890"),
                EmergencySms.parseNumbers("abc, 123, 081234567890, 0812 3456 7890, 1234567890123456789"));
        assertTrue(EmergencySms.parseNumbers(null).isEmpty());
    }

    @Test public void bodyWithLocation() {
        assertEquals("SOS dari Sari (Saling Jaga). Saya butuh bantuan. "
                        + "Lokasi: https://maps.google.com/?q=-6.200000,106.816666 (akurasi 12 m, 4 menit lalu). Baterai 21%.",
                EmergencySms.body("Sari", true, -6.2, 106.816666, 12.4f, 4, 21));
    }

    @Test public void bodyWithoutLocation() {
        String b = EmergencySms.body("Budi", false, 0, 0, 0, -1, -1);
        assertEquals("SOS dari Budi (Saling Jaga). Saya butuh bantuan. Lokasi GPS belum tersedia.", b);
        assertTrue(EmergencySms.body("Budi", true, 1, 2, 0, 0, 50).endsWith("q=1.000000,2.000000. Baterai 50%."));
    }
}

package com.salingjaga.app;

import static org.junit.Assert.*;

import java.io.File;
import java.util.List;
import org.junit.Test;

public class TilesTest {
    @Test public void projectsLikeOsm() {
        // Central London is OSM tile 10/511/340; the equator and prime meridian meet at the centre.
        assertEquals(511, (int) Tiles.lonToX(-0.1276, 10));
        assertEquals(340, (int) Tiles.latToY(51.5072, 10));
        assertEquals(512.0, Tiles.latToY(0, 10), 1e-9);
        assertEquals(512.0, Tiles.lonToX(0, 10), 1e-9);
        assertEquals(106.8272, Tiles.xToLon(Tiles.lonToX(106.8272, 15), 15), 1e-9);
        assertEquals(-6.1754, Tiles.yToLat(Tiles.latToY(-6.1754, 15), 15), 1e-9);
    }

    @Test public void planStaysWithinOsmPolicy() {
        for (double lat : new double[] {-6.2, 0, 45, 60}) {
            List<int[]> plan = Tiles.plan(lat, 106.8);
            assertTrue("too many detailed tiles at " + lat, Tiles.detailed(plan) <= 250);
            int maxZ = 0;
            for (int[] t : plan) maxZ = Math.max(maxZ, t[0]);
            assertTrue(maxZ >= 14);
        }
        int maxZ = 0;
        for (int[] t : Tiles.plan(-6.2, 106.8)) maxZ = Math.max(maxZ, t[0]);
        assertEquals(16, maxZ);   // street level near the equator
    }

    @Test public void countsOnlyFinishedTiles() throws Exception {
        File root = java.nio.file.Files.createTempDirectory("tiles").toFile();
        File a = Tiles.file(root, 15, 1, 2);
        a.getParentFile().mkdirs();
        assertTrue(a.createNewFile());
        assertTrue(new File(a.getParentFile(), "3.png.part").createNewFile());
        assertEquals(1, Tiles.count(root));
        assertEquals(0, Tiles.count(new File(root, "nothing")));
    }
}

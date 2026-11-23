package com.tilepad;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryInfoTest {
    private static int[] values(int charge, int minimum) { return new int[]{charge, minimum, 0, 0, 0, 0, 0, 0}; }
    private static BatteryTemperature.Info info(int minimum) { return new BatteryTemperature.Info(minimum, 1, 2, 3); }

    @Test public void nullArrayGivesNoInfo() { assertNull(BatteryTemperature.decodeInfo(null)); }
    @Test public void wrongLengthGivesNoInfo() {
        for (int length : new int[]{0, 1, 7, 9, 16}) {
            int[] v = new int[length];
            assertNull("length " + length, BatteryTemperature.decodeInfo(v));
            assertNull("length " + length, BatteryTemperature.decode(v));
        }
    }
    @Test public void chargeOutsideZeroTo200GivesNoInfo() {
        assertNull(BatteryTemperature.decodeInfo(values(-1, 14)));
        assertNull(BatteryTemperature.decodeInfo(values(201, 14)));
        assertNotNull(BatteryTemperature.decodeInfo(values(0, 14)));
        assertNotNull(BatteryTemperature.decodeInfo(values(200, 14)));
    }
    @Test public void temperatureOutsideMinus40To60GivesNoInfo() {
        assertNull(BatteryTemperature.decodeInfo(values(50, -41)));
        assertNull(BatteryTemperature.decodeInfo(values(50, 61)));
        assertNotNull(BatteryTemperature.decodeInfo(values(50, -40)));
        assertNotNull(BatteryTemperature.decodeInfo(values(50, 60)));
        assertEquals(-40, BatteryTemperature.decodeInfo(values(50, -40)).minimumC);
        assertEquals(60, BatteryTemperature.decodeInfo(values(50, 60)).minimumC);
    }
    @Test public void reservedByteValuesGiveNoInfo() {
        assertNull(BatteryTemperature.decodeInfo(values(50, 127)));
        assertNull(BatteryTemperature.decodeInfo(values(50, 255)));
        assertNull(BatteryTemperature.decodeInfo(values(255, 14)));
    }
    @Test public void fieldsComeFromIndices1_0_4_5() {
        BatteryTemperature.Info i = BatteryTemperature.decodeInfo(new int[]{55, 14, 3, 4, 77, 88, 9, 10});
        assertNotNull(i);
        assertEquals(14, i.minimumC);
        assertEquals(55, i.chargeRaw);
        assertEquals(77, i.outsideRaw);
        assertEquals(88, i.conditioningRaw);
    }
    @Test public void otherIndicesDoNotLeakIntoTheFields() {
        BatteryTemperature.Info a = BatteryTemperature.decodeInfo(new int[]{55, 14, 1, 2, 77, 88, 3, 4});
        BatteryTemperature.Info b = BatteryTemperature.decodeInfo(new int[]{55, 14, 9, 9, 77, 88, 9, 9});
        assertEquals(a.minimumC, b.minimumC); assertEquals(a.chargeRaw, b.chargeRaw);
        assertEquals(a.outsideRaw, b.outsideRaw); assertEquals(a.conditioningRaw, b.conditioningRaw);
    }
    @Test public void minimumMatchesDecode() {
        int[] v = {10, -12, 0, 0, 5, 6, 0, 0};
        assertEquals(BatteryTemperature.decode(v).intValue(), BatteryTemperature.decodeInfo(v).minimumC);
    }
    @Test public void infoIsNullExactlyWhenDecodeIsNull() {
        Random random = new Random(7);
        for (int n = 0; n < 5000; n++) {
            int length = random.nextInt(12) < 10 ? 8 : random.nextInt(12);
            int[] v = new int[length];
            for (int i = 0; i < length; i++) v[i] = random.nextInt(400) - 120;
            if (length == 8 && random.nextBoolean()) { v[0] = random.nextInt(260); v[1] = random.nextInt(130) - 50; }
            assertEquals(java.util.Arrays.toString(v), BatteryTemperature.decode(v) == null, BatteryTemperature.decodeInfo(v) == null);
        }
    }

    @Test public void freshAtExactlyFortyFiveSeconds() {
        BatteryStatus s = new BatteryStatus(info(14), 100000, BatteryStatus.Reader.IDLE, 20);
        assertTrue(s.fresh(100000));
        assertTrue(s.fresh(144999));
        assertTrue(s.fresh(145000));
        assertFalse(s.fresh(145001));
    }
    @Test public void staleLimitIsFortyFiveSeconds() { assertEquals(45000L, BatteryStatus.STALE_MS); }
    @Test public void noReplyIsNeverFresh() {
        assertFalse(BatteryStatus.NONE.fresh(0));
        assertFalse(BatteryStatus.NONE.fresh(1000));
        assertFalse(new BatteryStatus(null, 100000, BatteryStatus.Reader.READING, 20).fresh(100000));
    }
    @Test public void ageSecondsRoundsDownAndNeverGoesNegative() {
        BatteryStatus s = new BatteryStatus(info(14), 100000, BatteryStatus.Reader.IDLE, 20);
        assertEquals(0, s.ageSeconds(100000));
        assertEquals(0, s.ageSeconds(100999));
        assertEquals(1, s.ageSeconds(101000));
        assertEquals(44, s.ageSeconds(144999));
        assertEquals(0, s.ageSeconds(90000));
    }
    @Test public void degreesShowsCelsiusAndFahrenheit() {
        assertEquals("14°C (57°F)", BatteryStatus.degrees(14));
        assertEquals("0°C (32°F)", BatteryStatus.degrees(0));
        assertEquals("100°C (212°F)", BatteryStatus.degrees(100));
        assertEquals("-10°C (14°F)", BatteryStatus.degrees(-10));
        assertEquals("-40°C (-40°F)", BatteryStatus.degrees(-40));
    }
    @Test public void fahrenheitMinus40IsMinus40() { assertEquals(-40, BatteryStatus.fahrenheit(-40)); }
    @Test public void fahrenheitRoundsToTheNearestDegree() {
        assertEquals(32, BatteryStatus.fahrenheit(0));
        assertEquals(99, BatteryStatus.fahrenheit(37));   // 98.6
        assertEquals(68, BatteryStatus.fahrenheit(20));
        assertEquals(66, BatteryStatus.fahrenheit(19));   // 66.2
        assertEquals(64, BatteryStatus.fahrenheit(18));   // 64.4
        assertEquals(57, BatteryStatus.fahrenheit(14));   // 57.2
        assertEquals(-4, BatteryStatus.fahrenheit(-20));
    }
}

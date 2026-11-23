package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreconSnapshotRawTest {
    private static PreconditioningController controller(PreconditioningControllerTest.FakeVehicle car) {
        return new PreconditioningController(car, new PreconditioningControllerTest.Journal());
    }
    @Test public void beforeAnyReadingBothRawValuesAreMinusOne() {
        PreconditioningController c = controller(new PreconditioningControllerTest.FakeVehicle());
        assertEquals(-1, c.snapshot(1000).readyRaw);
        assertEquals(-1, c.snapshot(1000).enabledRaw);
    }
    @Test public void afterAReadingTheRawValuesAreTheCarsOwn() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        PreconditioningController c = controller(car);
        c.refresh(1000);
        assertEquals(1, c.snapshot(1000).readyRaw);
        assertEquals(2, c.snapshot(1000).enabledRaw);
    }
    @Test public void notReadyAndDisabledValuesAreCarried() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        car.reading = new VehiclePort.Reading(1, 0, 0, -1);
        PreconditioningController c = controller(car);
        c.refresh(1000);
        assertEquals(0, c.snapshot(1000).readyRaw);
        assertEquals(1, c.snapshot(1000).enabledRaw);
    }
    @Test public void unusualRawValuesAreCarriedUnchanged() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        car.reading = new VehiclePort.Reading(3, 0, 7, -1);
        PreconditioningController c = controller(car);
        c.refresh(1000);
        assertEquals(7, c.snapshot(1000).readyRaw);
        assertEquals(3, c.snapshot(1000).enabledRaw);
    }
    @Test public void rawValuesFollowTheLatestReading() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        PreconditioningController c = controller(car);
        c.refresh(1000);
        car.reading = new VehiclePort.Reading(1, 0, 0, -1);
        c.refresh(2000);
        assertEquals(0, c.snapshot(2000).readyRaw);
        assertEquals(1, c.snapshot(2000).enabledRaw);
    }
    @Test public void aFailedReadGoesBackToMinusOne() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        PreconditioningController c = controller(car);
        c.refresh(1000);
        car.readFails = true;
        c.refresh(2000);
        assertEquals(-1, c.snapshot(2000).readyRaw);
        assertEquals(-1, c.snapshot(2000).enabledRaw);
    }
    @Test public void aFailedFirstReadLeavesMinusOne() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        car.readFails = true;
        PreconditioningController c = controller(car);
        c.refresh(1000);
        assertEquals(-1, c.snapshot(1000).readyRaw);
        assertEquals(-1, c.snapshot(1000).enabledRaw);
    }
    @Test public void readingAfterARecoveredFailureCarriesValuesAgain() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        PreconditioningController c = controller(car);
        car.readFails = true; c.refresh(1000);
        car.readFails = false; c.refresh(2000);
        assertEquals(1, c.snapshot(2000).readyRaw);
        assertEquals(2, c.snapshot(2000).enabledRaw);
    }
    @Test public void rawValuesSurviveARequestInProgress() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        PreconditioningController c = controller(car);
        c.refresh(1000); assertTrue(c.start(1000)); c.refresh(2000);
        assertTrue(c.snapshot(2000).requested);
        assertEquals(1, c.snapshot(2000).readyRaw);
        assertEquals(2, c.snapshot(2000).enabledRaw);
    }
    @Test public void aFailedReadDuringARequestAlsoGivesMinusOne() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        PreconditioningController c = controller(car);
        c.refresh(1000); c.start(1000);
        car.readFails = true; c.refresh(2000);
        assertTrue(c.snapshot(2000).requested);
        assertEquals(-1, c.snapshot(2000).readyRaw);
        assertEquals(-1, c.snapshot(2000).enabledRaw);
    }
    @Test public void rawValuesAreCarriedWhileTheStateIsUnknown() {
        PreconditioningControllerTest.FakeVehicle car = new PreconditioningControllerTest.FakeVehicle();
        car.reading = new VehiclePort.Reading(2, 2, 1, -1); // reserved indicator: UNKNOWN state
        PreconditioningController c = controller(car);
        c.refresh(1000);
        assertEquals(PreconditioningSnapshot.State.UNKNOWN, c.snapshot(1000).state);
        assertEquals(1, c.snapshot(1000).readyRaw);
        assertEquals(2, c.snapshot(1000).enabledRaw);
    }
    @Test public void shorterSnapshotConstructorsDefaultToMinusOne() {
        PreconditioningSnapshot a = new PreconditioningSnapshot(PreconditioningSnapshot.State.IDLE, false, true, false, 0, -1, 0, "x");
        PreconditioningSnapshot b = new PreconditioningSnapshot(PreconditioningSnapshot.State.IDLE, false, true, false, 0, -1, 0, "x", 5);
        assertEquals(-1, a.readyRaw); assertEquals(-1, a.enabledRaw);
        assertEquals(-1, b.readyRaw); assertEquals(-1, b.enabledRaw);
        assertEquals(-1, PreconditioningSnapshot.checking().readyRaw);
        assertEquals(-1, PreconditioningSnapshot.checking().enabledRaw);
    }
    @Test public void fullConstructorKeepsTheRawValues() {
        PreconditioningSnapshot s = new PreconditioningSnapshot(PreconditioningSnapshot.State.IDLE, false, true, false, 0, -1, 0, "x", 0, 1, 2);
        assertEquals(1, s.readyRaw); assertEquals(2, s.enabledRaw);
    }
}

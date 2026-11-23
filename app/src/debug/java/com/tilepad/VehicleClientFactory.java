package com.tilepad;

import android.os.Build;

/** Explicit emulator-only simulation, excluded from the release APK. */
final class VehicleClientFactory {
    static VehiclePort create() {
        // The emulator gets the scriptable car (see SimulatedCar for its debug.tilepad.* properties).
        return simulated() ? new SimulatedCar() : new OemVehicleClient();
    }

    /** True on an emulator, where {@link #create()} returns the simulator. */
    static boolean simulated() { return Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"); }
}

package com.vys.vysdragons.dragon;

import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.manager.MountManager;
import com.ticxo.modelengine.api.mount.controller.MountControllerTypes;
import org.bukkit.entity.Entity;

/**
 * ModelEngine's getMountManager() is declared with an intersection-type bound
 * ({@code <T extends MountManager & BehaviorManager<...>>}) that Kotlin can't infer,
 * so the calls that need it live here in Java.
 */
public final class Mounts {
    private Mounts() {}

    public static MountManager of(ActiveModel model) {
        if (model == null) return null;
        return model.getMountManager().orElse(null);
    }

    public static void enable(ActiveModel model, boolean drive, boolean ride) {
        MountManager manager = of(model);
        if (manager == null) return;
        manager.setCanDrive(drive);
        manager.setCanRide(ride);
    }

    /** Seats {@code passenger} on the bone {@code seat}. False if the seat is missing or full. */
    public static boolean mountPassenger(ActiveModel model, String seat, Entity passenger) {
        MountManager manager = of(model);
        if (manager == null) return false;
        return manager.mountPassenger(seat, passenger, MountControllerTypes.WALKING);
    }
}

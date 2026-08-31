package com.sevtinge.hyperceiler.libhook.rules.systemframework;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VolumeUpTorchTest {
    @Test public void onlyTorchValueEnablesAction() {
        assertTrue(VolumeUpTorch.isTorchAction("toggle_torch"));
        assertFalse(VolumeUpTorch.isTorchAction("none"));
        assertFalse(VolumeUpTorch.isTorchAction("turn_on_torch"));
        assertFalse(VolumeUpTorch.isTorchAction(null));
    }
}

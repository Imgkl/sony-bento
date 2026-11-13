package com.sonybento.volume;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Durable recovery-state tests only; no Android runtime or device operations. */
public final class RecoveryGuardTest {
    private static final String BOOT = "00000000-0000-0000-0000-000000000001";

    public static void main(String[] args) throws Exception {
        String filter = args.length == 0 ? "" : args[0];
        if ("interrupted restoration remains unclean after cooldown".contains(filter)) {
            interruptedRestoration();
            System.out.println("PASS interrupted restoration remains unclean after cooldown");
        }
        if ("verified restoration reopens clean without losing cooldown".contains(filter)) {
            verifiedRestoration();
            System.out.println("PASS verified restoration reopens clean without losing cooldown");
        }
        if ("legacy cooldown remains unverified across reopen".contains(filter)) {
            legacyCooldown();
            System.out.println("PASS legacy cooldown remains unverified across reopen");
        }
        if ("active app session is resumable but pending restoration is not".contains(filter)) {
            pendingRestoration();
            System.out.println("PASS active app session is resumable but pending restoration is not");
        }
        if ("verified restoration preserves the two-attempt recovery budget".contains(filter)) {
            recoveryBudget();
            System.out.println("PASS verified restoration preserves the two-attempt recovery budget");
        }
        if ("explicit reset clears budget but preserves the cleanup obligation".contains(filter)) {
            resetOwnership();
            System.out.println("PASS explicit reset clears budget but preserves the cleanup obligation");
        }
        if ("explicit reset treats corrupt markers as unknown ownership".contains(filter)) {
            resetCorruptState();
            System.out.println("PASS explicit reset treats corrupt markers as unknown ownership");
        }
        if ("explicit reset preserves known clean ownership".contains(filter)) {
            resetCleanState();
            System.out.println("PASS explicit reset preserves known clean ownership");
        }
    }

    private static void resetCleanState() throws Exception {
        for (boolean completed : new boolean[]{false, true}) {
            Path directory = Files.createTempDirectory("volume-recovery-clean-reset-");
            try {
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                    if (completed) {
                        guard.begin();
                        guard.reserveRecovery(100_000);
                        guard.completeRecovery();
                    }
                }
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                    check(!guard.unclean(), "Known clean state acquired an unnecessary restoration obligation");
                    check(guard.waitMillis(100_001) == 0, "Explicit reset retained a verified cooldown");
                }
            } finally {
                remove(directory);
            }
        }
    }

    private static void resetCorruptState() throws Exception {
        for (String contents : new String[]{"not a recovery marker\n", "phase=\\uZZZZ\n"}) {
            Path directory = Files.createTempDirectory("volume-recovery-corrupt-reset-");
            try {
                Files.writeString(directory.resolve("recovery.state"), contents);
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                    check(guard.unclean(), "Corrupt state reset lost the unknown cleanup obligation");
                    check(guard.waitMillis(100_000) == 0, "Corrupt state blocked explicit recovery");
                }
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, false)) {
                    check(guard.unclean(), "Explicit reset did not replace the corrupt marker durably");
                }
            } finally {
                remove(directory);
            }
        }
    }

    private static void resetOwnership() throws Exception {
        for (String state : new String[]{"active", "restoring", "disabled"}) {
            Path directory = Files.createTempDirectory("volume-recovery-reset-");
            try {
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                    guard.begin();
                    if (!state.equals("active")) guard.reserveRecovery(100_000);
                    if (state.equals("disabled")) guard.disable();
                }
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                    check(guard.waitMillis(100_001) == 0, "Explicit reset retained its old retry barrier");
                    check(guard.unclean(), "Reset forgot the existing controller before READY/STOP: " + state);
                    check(!guard.needsRestoration(), "Explicit retry cannot resume the app-owned controller");
                }
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, false)) {
                    check(guard.unclean(), "Cleanup obligation was not persisted by explicit reset");
                    // STOP before the new READY must still perform one guarded stock restoration.
                    guard.reserveRecovery(100_002);
                    check(guard.waitMillis(100_003) == 59_999, "Reset did not clear the old recovery budget");
                }
            } finally {
                remove(directory);
            }
        }
    }

    private static void recoveryBudget() throws Exception {
        Path directory = Files.createTempDirectory("volume-recovery-budget-");
        try {
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                guard.begin();
                guard.reserveRecovery(100_000);
                guard.completeRecovery();
            }
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, false)) {
                guard.begin();
                guard.reserveRecovery(160_001);
                guard.completeRecovery();
            }
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, false)) {
                boolean disabled = false;
                try { guard.waitMillis(2_000_000); }
                catch (IOException expected) { disabled = true; }
                check(disabled, "Verified restoration reset the bounded recovery budget");
            }
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                check(guard.waitMillis(2_000_000) == 0, "Explicit recovery could not clear the guard");
            }
        } finally {
            remove(directory);
        }
    }

    private static void pendingRestoration() throws Exception {
        Path directory = Files.createTempDirectory("volume-recovery-pending-");
        try {
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                guard.begin();
                check(guard.unclean(), "Active ownership must remain visible to restore-only cleanup");
                check(!guard.needsRestoration(), "Active app session cannot resume its controller");
                guard.reserveRecovery(100_000);
                check(guard.needsRestoration(), "Interrupted stock recovery was treated as resumable");
                guard.completeRecovery();
                check(!guard.needsRestoration(), "Verified stock recovery is still pending");
            }
        } finally {
            remove(directory);
        }
    }

    private static void legacyCooldown() throws Exception {
        Path directory = Files.createTempDirectory("volume-recovery-legacy-");
        try {
            Files.writeString(directory.resolve("recovery.state"),
                    "phase=cooldown\nfailures=1\nlastRecovery=100000\nuntil=160000\nbootId=" + BOOT + "\n");
            for (int attempt = 0; attempt < 2; attempt++) {
                try (ShellVolumeHelper.RecoveryGuard guard = open(directory, false)) {
                    check(guard.unclean(), "Legacy reservation was mistaken for verified restoration");
                    check(guard.waitMillis(100_001) == 59_999, "Migration cleared the legacy cooldown");
                }
            }
        } finally {
            remove(directory);
        }
    }

    private static void verifiedRestoration() throws Exception {
        Path directory = Files.createTempDirectory("volume-recovery-verified-");
        try {
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                guard.begin();
                guard.reserveRecovery(100_000);
                guard.completeRecovery();
            }
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, false)) {
                check(!guard.unclean(), "Verified restoration still requires stock recovery");
                check(guard.waitMillis(100_001) == 59_999, "Verification cleared the recovery cooldown");
                check(guard.waitMillis(160_001) == 0, "Verified cooldown never expires");
            }
        } finally {
            remove(directory);
        }
    }

    private static void interruptedRestoration() throws Exception {
        Path directory = Files.createTempDirectory("volume-recovery-interrupted-");
        try {
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, true)) {
                guard.begin();
                guard.reserveRecovery(100_000);
            }
            // Reopening models losing the helper after its reservation reached disk.
            try (ShellVolumeHelper.RecoveryGuard guard = open(directory, false)) {
                check(guard.waitMillis(100_001) == 59_999, "Interrupted recovery lost its cooldown");
                check(guard.waitMillis(160_001) == 0, "Recovery did not become eligible after cooldown");
                check(guard.unclean(), "Unverified restoration became a clean takeover opportunity");
            }
        } finally {
            remove(directory);
        }
    }

    private static ShellVolumeHelper.RecoveryGuard open(Path directory, boolean reset) throws IOException {
        return ShellVolumeHelper.RecoveryGuard.open(directory.toFile(), reset, BOOT);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void remove(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}

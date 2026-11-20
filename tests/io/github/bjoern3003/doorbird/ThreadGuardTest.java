// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

public final class ThreadGuardTest {
    public static void main(String[] args) throws Exception {
        ThreadGuard.run(() -> { throw new AssertionError("contained"); });
        Thread thread = ThreadGuard.start("doorbird-test", () -> { throw new OutOfMemoryError("contained"); });
        thread.join(1000);
        check(thread.isAlive(), false);
        System.out.println("ThreadGuardTest passed");
    }

    private static void check(boolean actual, boolean expected) {
        if (actual != expected) throw new AssertionError("unexpected result");
    }
}

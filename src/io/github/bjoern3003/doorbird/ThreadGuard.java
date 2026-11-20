// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

/** Keeps a plugin worker failure from escaping onto Kiosk Satellite's process-wide uncaught-exception handler. */
public final class ThreadGuard {
    private ThreadGuard() {}

    public static Thread create(String name, Runnable task) {
        Thread thread = new Thread(() -> run(task), name);
        thread.setDaemon(true);
        return thread;
    }

    public static Thread create(Runnable task, String name) {
        return create(name, task);
    }

    public static Thread start(String name, Runnable task) {
        Thread thread = create(name, task);
        thread.start();
        return thread;
    }

    public static void run(Runnable task) {
        try {
            task.run();
        } catch (Throwable error) {
            System.err.println("DoorBird plugin worker " + Thread.currentThread().getName() + " failed: " + error);
        }
    }
}

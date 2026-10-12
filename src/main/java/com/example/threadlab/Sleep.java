package com.example.threadlab;

/** Stand-in for blocking I/O: the calling thread parks and does no CPU work. */
final class Sleep {

    private Sleep() {
    }

    static void ms(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

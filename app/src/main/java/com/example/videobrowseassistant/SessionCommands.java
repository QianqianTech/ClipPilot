package com.example.videobrowseassistant;

/**
 * Explicit, in-process command channel from the activity and the notification receiver to the
 * running accessibility service.
 *
 * <p>SharedPreferences remain the persisted state, but they are no longer used as a message bus:
 * callers first persist state (through {@link SessionStore} / {@link InteractionSession}) and then
 * dispatch a command so the service reacts immediately and deterministically. All calls happen on
 * the main thread (activity callbacks, broadcast receivers and the service share it).</p>
 *
 * <p>If the service is not connected the command is dropped and {@link #dispatch} returns
 * {@code false}; the service re-reads the persisted state when it connects or receives its next
 * accessibility event, so nothing is lost.</p>
 */
final class SessionCommands {
    enum Command {
        /** A new session snapshot was persisted. */
        SESSION_STARTED,
        /** The session was stopped and its state cleared. */
        SESSION_STOPPED,
        /** Browsing was paused and a page-text capture was requested. */
        PAUSE_AND_CAPTURE,
        /** Browsing was resumed and short-lived comment data cleared. */
        RESUME_BROWSING,
        /** A fresh draft should be written into the focused input field. */
        FILL_DRAFT,
        /** The keep-screen-on preference changed. */
        KEEP_SCREEN_CHANGED,
        /** A comment draft was prepared; refresh the notification actions. */
        DRAFT_UPDATED
    }

    interface Handler {
        void onSessionCommand(Command command);
    }

    private static Handler handler;

    private SessionCommands() {
    }

    static synchronized void register(Handler newHandler) {
        handler = newHandler;
    }

    static synchronized void unregister(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = null;
        }
    }

    static synchronized boolean isConnected() {
        return handler != null;
    }

    /** Delivers the command to the connected service; returns {@code false} when none is connected. */
    static boolean dispatch(Command command) {
        Handler target;
        synchronized (SessionCommands.class) {
            target = handler;
        }
        if (target == null || command == null) {
            return false;
        }
        target.onSessionCommand(command);
        return true;
    }
}

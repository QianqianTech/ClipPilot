package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SessionCommandsTest {
    private final List<SessionCommands.Command> received = new ArrayList<>();
    private final SessionCommands.Handler handler = received::add;

    @After
    public void tearDown() {
        SessionCommands.unregister(handler);
    }

    @Test
    public void commandsAreDroppedWhenNoServiceIsConnected() {
        assertFalse(SessionCommands.isConnected());
        assertFalse(SessionCommands.dispatch(SessionCommands.Command.SESSION_STARTED));
    }

    @Test
    public void connectedServiceReceivesCommandsInOrder() {
        SessionCommands.register(handler);
        assertTrue(SessionCommands.dispatch(SessionCommands.Command.PAUSE_AND_CAPTURE));
        assertTrue(SessionCommands.dispatch(SessionCommands.Command.RESUME_BROWSING));
        assertEquals(2, received.size());
        assertEquals(SessionCommands.Command.RESUME_BROWSING, received.get(1));
        assertFalse(SessionCommands.dispatch(null));
    }

    @Test
    public void unregisteringAStaleHandlerKeepsTheCurrentOne() {
        SessionCommands.register(handler);
        SessionCommands.unregister(command -> { });
        assertTrue(SessionCommands.isConnected());
        SessionCommands.unregister(handler);
        assertFalse(SessionCommands.isConnected());
    }
}

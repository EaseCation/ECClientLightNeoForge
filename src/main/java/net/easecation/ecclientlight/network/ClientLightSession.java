package net.easecation.ecclientlight.network;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.easecation.ecclientlight.ECClientLight;
import net.minecraft.network.Connection;

/** Tracks the negotiated capability by connection; absence always means server-computed light. */
public final class ClientLightSession {

    private static final Set<Connection> CONFIRMED_CONNECTIONS = Collections.newSetFromMap(new WeakHashMap<>());

    private ClientLightSession() {
    }

    public static synchronized void confirm(Connection connection) {
        if (CONFIRMED_CONNECTIONS.add(connection)) {
            ECClientLight.LOGGER.info(
                "[ECClientLight] Configuration ACK received; client light computation enabled for this connection"
            );
        }
    }

    public static synchronized boolean isConfirmed(Connection connection) {
        return CONFIRMED_CONNECTIONS.contains(connection);
    }

    public static synchronized void reset(Connection connection, String reason) {
        if (CONFIRMED_CONNECTIONS.remove(connection)) {
            ECClientLight.LOGGER.debug("[ECClientLight] Client light computation disabled ({})", reason);
        }
    }
}

package de.peakverse.core.protocol;

import java.util.Arrays;

/**
 * Ein Paket auf dem Kanal {@value #CHANNEL}. Es ist entweder fuer ein Modul bestimmt ({@link ModuleFrame}) oder ein Steuerpaket
 * des Cores ({@link CoreFrame}, z. B. Handshake). Der Inhalt der Nutzdaten ist Sache des Empfaengers; der Core reicht ihn nur weiter.
 */
public sealed interface Frame permits Frame.ModuleFrame, Frame.CoreFrame {
    /** Name des Plugin-Messaging-Kanals. */
    String CHANNEL = "peakcore:main";

    /** Nutzdaten fuer das Modul mit der angegebenen ID. */
    record ModuleFrame(String moduleId, byte[] payload) implements Frame {
        public ModuleFrame {
            if (!ModuleIds.isValid(moduleId)) throw new IllegalArgumentException("Ungueltige Modul-ID: " + moduleId);
            if (payload == null) throw new IllegalArgumentException("payload fehlt");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ModuleFrame other && moduleId.equals(other.moduleId) && Arrays.equals(payload, other.payload);
        }

        @Override
        public int hashCode() {
            return 31 * moduleId.hashCode() + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "ModuleFrame[" + moduleId + ", " + payload.length + " Bytes]";
        }
    }

    /** Steuerpaket des Cores selbst (Handshake u. a., ab Phase 4). */
    record CoreFrame(byte[] payload) implements Frame {
        public CoreFrame {
            if (payload == null) throw new IllegalArgumentException("payload fehlt");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CoreFrame other && Arrays.equals(payload, other.payload);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "CoreFrame[" + payload.length + " Bytes]";
        }
    }
}

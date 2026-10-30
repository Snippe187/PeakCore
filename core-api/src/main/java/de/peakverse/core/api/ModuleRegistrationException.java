package de.peakverse.core.api;

/** Die Anmeldung eines Moduls wurde abgelehnt (doppelte ID oder zu neue API-Anforderung). Die Meldung ist fuer Admins lesbar. */
public final class ModuleRegistrationException extends RuntimeException {
    public ModuleRegistrationException(String message) {
        super(message);
    }
}

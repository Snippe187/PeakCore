package de.peakverse.core.api;

/** Nachweis einer erfolgreichen Modul-Anmeldung. Wird auch automatisch beendet, wenn das Plugin des Moduls abgeschaltet wird. */
public interface ModuleRegistration {
    /** Das angemeldete Modul. */
    ModuleInfo info();

    /** Meldet das Modul wieder ab. Mehrfaches Aufrufen schadet nicht. */
    void unregister();
}

package de.peakverse.core.protocol;

/** Regeln fuer Modul-IDs: 2-24 Zeichen aus a-z, 0-9 und _, beginnend mit einem Buchstaben. Gilt auf dem Draht und in der API. */
public final class ModuleIds {
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 24;

    private ModuleIds() {}

    public static boolean isValid(String id) {
        if (id == null || id.length() < MIN_LENGTH || id.length() > MAX_LENGTH) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean letter = c >= 'a' && c <= 'z';
            boolean digitOrUnderscore = (c >= '0' && c <= '9') || c == '_';
            if (i == 0 ? !letter : !(letter || digitOrUnderscore)) return false;
        }
        return true;
    }
}

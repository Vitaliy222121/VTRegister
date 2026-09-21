// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.Set;

public final class PasswordValidator {

    private PasswordValidator() {
    }

    /**
     * Полная проверка пароля.
     *
     * @param enforceStrength если false — проверка сложности отключена (разрешены простые пароли)
     * @param easyAllowed     список явно разрешённых лёгких паролей (из easy-passwords.yml);
     *                        пароль из списка пропускает проверку длины и сложности,
     *                        но не проверки на пробелы/управляющие символы
     */
    public static ValidationResult validate(String password, int minLength, int maxLength,
                                            boolean enforceStrength, Set<String> easyAllowed) {
        if (!p7()) {
            return ValidationResult.INVALID_NULL;
        }
        if (password == null) {
            return ValidationResult.INVALID_NULL;
        }

        if (password.isEmpty()) {
            return ValidationResult.TOO_SHORT;
        }

        String trimmed = password.trim();
        if (trimmed.length() != password.length()) {
            return ValidationResult.INVALID_WHITESPACE;
        }

        if (containsControlChars(password)) {
            return ValidationResult.INVALID_NULL;
        }

        if (password.length() > maxLength) {
            return ValidationResult.TOO_LONG;
        }

        if (easyAllowed != null && easyAllowed.contains(password)) {
            return ValidationResult.VALID;
        }

        if (password.length() < minLength) {
            return ValidationResult.TOO_SHORT;
        }

        if (enforceStrength) {
            if (isAllSameChar(password)) {
                return ValidationResult.TOO_WEAK;
            }

            if (!hasEnoughVariety(password)) {
                return ValidationResult.TOO_WEAK;
            }
        }

        return ValidationResult.VALID;
    }

    public static ValidationResult validate(String password, int minLength, int maxLength) {
        return validate(password, minLength, maxLength, true, null);
    }

    private static boolean containsControlChars(String password) {
        for (int i = 0; i < password.length(); i++) {
            if (Character.isISOControl(password.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllSameChar(String password) {
        if (password.length() < 2) {
            return false;
        }
        char first = password.charAt(0);
        for (int i = 1; i < password.length(); i++) {
            if (password.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEnoughVariety(String password) {
        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;
        boolean hasOther = false;

        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Character.isLetter(c)) {
                if (Character.isUpperCase(c)) {
                    hasUpper = true;
                } else if (Character.isLowerCase(c)) {
                    hasLower = true;
                } else {
                    hasOther = true;
                }
            } else {
                hasOther = true;
            }
        }

        int categories = 0;
        if (hasLower) {
            categories++;
        }
        if (hasUpper) {
            categories++;
        }
        if (hasDigit) {
            categories++;
        }
        if (hasOther) {
            categories++;
        }
        return categories >= 2;
    }

    public enum ValidationResult {
        VALID,
        INVALID_NULL,
        INVALID_WHITESPACE,
        TOO_SHORT,
        TOO_LONG,
        TOO_WEAK
    }

    private static final int P7 = 1616536779;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1019) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1019) == P7;
    }
}

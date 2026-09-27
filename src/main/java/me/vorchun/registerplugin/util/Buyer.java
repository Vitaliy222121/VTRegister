// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

/**
 * Метка покупателя. Подставляется при сборке: BUYER=имя bash tools/build.sh.
 * Класс входит в подпись сборки — правка метки ломает jar (сборка «повреждена»).
 */
public final class Buyer {
    public static final String ID = "public";

    private Buyer() {
    }
}

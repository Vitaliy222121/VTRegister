// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Быстрый поиск IPv4 в списке подсетей (CIDR). Диапазоны сортируются и
 * сливаются один раз, поиск — двоичный: сотни тысяч подсетей, проверка
 * за микросекунды. Чистая Java — без Bukkit.
 */
public final class CidrList {

    private final long[] starts;
    private final long[] ends;

    private CidrList(long[] starts, long[] ends) {
        this.starts = starts;
        this.ends = ends;
    }

    public static final CidrList EMPTY = new CidrList(new long[0], new long[0]);

    public int size() {
        return starts.length;
    }

    /** Разбор строк вида 1.2.3.0/24 или 1.2.3.4 (комментарии # и мусор пропускаются). */
    public static CidrList parse(Iterable<String> lines) {
        List<long[]> r = new ArrayList<>();
        for (String raw : lines) {
            String s = raw == null ? "" : raw.trim();
            int hash = s.indexOf('#');
            if (hash >= 0) {
                s = s.substring(0, hash).trim();
            }
            if (s.isEmpty()) {
                continue;
            }
            int slash = s.indexOf('/');
            long ip = ipv4(slash < 0 ? s : s.substring(0, slash));
            if (ip < 0) {
                continue;
            }
            int bits = 32;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(s.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (bits < 8 || bits > 32) {
                    continue; // /0–/7 — явная ошибка списка, не блокируем пол-интернета
                }
            }
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            long start = ip & mask;
            r.add(new long[]{start, start | (~mask & 0xFFFFFFFFL)});
        }
        r.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] x : r) {
            if (!merged.isEmpty() && x[0] <= merged.get(merged.size() - 1)[1] + 1) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], x[1]);
            } else {
                merged.add(x);
            }
        }
        long[] st = new long[merged.size()];
        long[] en = new long[merged.size()];
        for (int i = 0; i < st.length; i++) {
            st[i] = merged.get(i)[0];
            en[i] = merged.get(i)[1];
        }
        return new CidrList(st, en);
    }

    public boolean contains(String ip) {
        long v = ipv4(ip);
        if (v < 0 || starts.length == 0) {
            return false;
        }
        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= v) {
                if (v <= ends[mid]) {
                    return true;
                }
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return false;
    }

    /** IPv4 → число 0..2^32-1, иначе -1. */
    static long ipv4(String s) {
        if (s == null) {
            return -1;
        }
        String[] p = s.trim().split("\\.");
        if (p.length != 4) {
            return -1;
        }
        long v = 0;
        for (String x : p) {
            int n;
            try {
                n = Integer.parseInt(x);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (n < 0 || n > 255) {
                return -1;
            }
            v = (v << 8) | n;
        }
        return v;
    }
}

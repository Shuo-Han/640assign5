package edu.wisc.cs.sdn.simpledns;

import java.util.Arrays;

class CIDR {
    int ip;
    int prefix;
    int masked;

    public CIDR(String s) {
        parseCIDR(s);
    }

    private void parseCIDR(String s) {
        String[] parts = s.split("/");
        if (parts.length != 2)
            return;

        prefix = Integer.parseInt(parts[1]);
        ip = Arrays.stream(parts[0].split("\\.")).map(Integer::parseInt).reduce(0,
                (x, y) -> y + (x << 8));

        masked = mask(ip, prefix);
    }

    private static int mask(int ip, int prefix) {
        // -1 is all bits with 1
        return ip & (-1 << (32 - prefix));
    }

    public boolean match(int ip) {
        return masked == mask(ip, prefix);
    }
}

package edu.wisc.cs.sdn.simpledns;

/**
 * EC2
 */
public class EC2 {
    public CIDR cidr;
    public String region;

    public EC2(CIDR cidr, String region) {
        this.cidr = cidr;
        this.region = region;
    }

    public boolean match(int ip) {
        return cidr.match(ip);
    }
}

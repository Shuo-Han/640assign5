package edu.wisc.cs.sdn.simpledns;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class SimpleDNS {
    public static final boolean DEBUG = true;

    public static final void debug(String s) {
        if (DEBUG) System.out.println("[DEBUG] " + s);
    }

    public static void main(String[] args) {
        String rootDNSHostname = null;
        String csvFilename = null;

        try {
            for (int i = 0; i < args.length; ++i) {
                if (args[i].equals("-r")) {
                    if (i + 1 < args.length) {
                        rootDNSHostname = args[++i];
                    } else {
                        System.err.println("root DNS server not given after -r");
                        usage();
                    }
                } else if (args[i].equals("-e")) {
                    if (i + 1 < args.length) {
                        csvFilename = args[++i];
                    } else {
                        System.err.println("csv file not given after -e");
                        usage();
                    }
                } else {
                    System.err.println("unknown option: " + args[i]);
                    usage();
                }
            }

            if (rootDNSHostname == null || csvFilename == null)
                usage();
        } catch (Exception e) {
            usage();
        }
        debug("cli args ok!");

        // read and parse csv
        List<EC2> ec2List = readAndParseCsv(csvFilename);
        debug("done parsing csv, total " + ec2List.size() + " entries!");

        // check rootDNS hostname valid
        try {
            InetAddress rootDNS = InetAddress.getByName(rootDNSHostname);
            new DNSServer(rootDNS, ec2List);
        } catch (UnknownHostException e) {
            System.err.println("root DNS hostname invalid: " + rootDNSHostname);
            System.err.println("See usage example below:\n");
            usage();
        }

    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println(
                "\tjava -cp bin edu.wisc.cs.sdn.simpledns.SimpleDNS -r <root server ip> -e <ec2 csv>");
        System.out.println("Options:");
        System.out.println("\t-r <root server ip>:");
        System.out.println("\t\tspecifies the IP address of a root DNS server");
        System.out.println("\t-e <ec2 csv>:");
        System.out.println("\t\tspecifies the path to a comma-separated variable (CSV) file");
        System.out.println("\t\tthat contains entries specifying the IP address ranges");
        System.out.println("\t\tfor each Amazon EC2 region");
        System.out.println("Example:");
        System.out.println(
                "\tjava -cp bin edu.wisc.cs.sdn.simpledns.SimpleDNS -r a.root-servers.net -e ec2.csv");

        System.exit(0);
    }

    private static List<EC2> readAndParseCsv(String fileName) {
        List<EC2> ec2List = new ArrayList<EC2>();

        // read file into stream, try-with-resources
        try (Stream<String> stream = Files.lines(Paths.get(fileName))) {

            stream.map(s -> s.split(",")).forEach(l -> {
                CIDR cidr = new CIDR(l[0]);
                ec2List.add(new EC2(cidr, l[1]));
            });

        } catch (FileNotFoundException e) {
            System.err.println("csv file: " + fileName + " not found");
        } catch (IOException e) {
            System.err.println("Caught exception when opening and parsing csv file: " + fileName);
            if (DEBUG)
                e.printStackTrace();
        }

        return ec2List;
    }
}
 
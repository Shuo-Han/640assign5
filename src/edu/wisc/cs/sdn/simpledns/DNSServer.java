package edu.wisc.cs.sdn.simpledns;

import static edu.wisc.cs.sdn.simpledns.SimpleDNS.debug;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;
import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataAddress;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataName;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;

public class DNSServer {
    private static final int BUFFER_SIZE = 1024;
    private static final int PORT_NUMBER = 8053;
    private InetAddress rootDNS;
    private List<EC2> ec2List;
    private DatagramSocket socket;

    public DNSServer(InetAddress rootDNS, List<EC2> ec2List) {
        this.rootDNS = rootDNS;
        this.ec2List = ec2List;

        startServer();
    }

    private void startServer() {
        debug("starting server...");
        try {
            socket = new DatagramSocket(PORT_NUMBER);

            listen();
        } catch (SocketException e) {
            System.err.println("Error while starting DatagramSocket on port 8053");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Caught exception when listening and processing requests.");
            e.printStackTrace();
        }
    }

    private void listen() throws IOException {
        debug("listening on port 8053");

        while (true) {
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            byte[] data = packet.getData();
            DNS dns = DNS.deserialize(data, data.length);

            // Your server only needs to handle opcode 0 (standard query)
            // and query types A, AAAA, CNAME, and NS.
            // You can silently drop all other client queries.
            if (dns.getOpcode() != DNS.OPCODE_STANDARD_QUERY)
                continue; // drop silently

            List<DNSQuestion> questions = dns.getQuestions();
            // remove type if not A, AAAA, CNAME, NS
            questions.removeIf(q -> q.getType() != DNS.TYPE_A && q.getType() != DNS.TYPE_AAAA
                    && q.getType() != DNS.TYPE_CNAME && q.getType() != DNS.TYPE_NS);
            dns.setQuestions(questions);
            if (questions.size() < 1)
                continue;

            DNS answer;
            if (dns.isRecursionDesired()) {
                answer = recursiveLookup(dns, rootDNS);
            } else {
                // non-recursive lookup
                answer = queryDNS(dns, rootDNS);
            }

            DatagramPacket answerPacket = new DatagramPacket(answer.serialize(), answer.getLength(),
                    packet.getAddress(), packet.getPort());
            socket.send(answerPacket);

        }
    }

    DNS queryDNS(DNS dns, InetAddress nsAddr) throws IOException {
        debug("Now query to NS: " + nsAddr + " dns: " + dns);

        DNS send = new DNS();
        send.setOpcode(DNS.OPCODE_STANDARD_QUERY);
        send.setQuestions(dns.getQuestions());
        send.setId(dns.getId());
        send.setRecursionAvailable(false);
        send.setQuery(true);

        DatagramPacket sendPacket =
                new DatagramPacket(send.serialize(), send.getLength(), nsAddr, 53);
        socket.send(sendPacket);

        byte[] receiveBuffer = new byte[BUFFER_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        socket.receive(receivePacket);

        DNS receive = DNS.deserialize(receivePacket.getData(), receivePacket.getLength());

        // if is A, and has answer, check if is EC2
        if (!dns.getQuestions().isEmpty() && dns.getQuestions().get(0).getType() == DNS.TYPE_A
                && !receive.getAnswers().isEmpty()) {
            checkAddressMatchEC2(receive);
        }

        return receive;
    }

    DNS recursiveLookup(DNS dns, InetAddress nsAddr) throws IOException {
        DNS receive = queryDNS(dns, nsAddr);
        debug("receive: " + receive);

        if (receive.getAnswers().isEmpty()) {
            // if no answer, recursively call to authority
            for (DNSResourceRecord auth : receive.getAuthorities()) {
                if (auth.getType() != DNS.TYPE_NS)
                    continue;
                DNSRdataName authData = (DNSRdataName) auth.getData();

                for (DNSResourceRecord additional : receive.getAdditional()) {
                    if (authData.getName().equals(additional.getName())
                            && additional.getType() == DNS.TYPE_A) {
                        DNSRdataAddress addrData = (DNSRdataAddress) additional.getData();

                        debug("Found auth==additional match! " + additional);
                        return recursiveLookup(dns, addrData.getAddress());
                    }
                }
            }

            // don't have additional to query, use auth
            for (DNSResourceRecord auth : receive.getAuthorities()) {
                if (auth.getType() != DNS.TYPE_NS)
                    continue;
                DNSRdataName authData = (DNSRdataName) auth.getData();
                return recursiveLookup(dns, InetAddress.getByName(authData.getName()));
            }
        } else if (!dns.getQuestions().isEmpty()
                && (dns.getQuestions().get(0).getType() == DNS.TYPE_A
                        || dns.getQuestions().get(0).getType() == DNS.TYPE_AAAA)) {
            // has answer!
            List<DNSResourceRecord> answers = receive.getAnswers();
            int size = answers.size();
            for (int i = 0; i < size; ++i) {
                DNSResourceRecord answer = answers.get(i);
                if (answer.getType() == DNS.TYPE_CNAME) {
                    DNS newDNS = new DNS();
                    newDNS.setOpcode(DNS.OPCODE_STANDARD_QUERY);
                    newDNS.setQuery(true);
                    newDNS.setTruncated(false);
                    newDNS.setAuthoritative(false);
                    newDNS.setId(receive.getId());

                    debug("preparing for question because of previous answer: " + answer.getData());
                    DNSQuestion question = new DNSQuestion(
                            ((DNSRdataName) answer.getData()).getName(), DNS.TYPE_A);
                    newDNS.addQuestion(question);

                    newDNS = recursiveLookup(newDNS, rootDNS);
                    debug("AFTER CNAME: " + newDNS);
                    for (DNSResourceRecord newAnswer : newDNS.getAnswers()) {
                        receive.addAnswer(newAnswer);
                    }
                }
            }
        }

        return receive;
    }

    void checkAddressMatchEC2(DNS dns) {
        List<DNSResourceRecord> answers = dns.getAnswers();
        for (int i = 0; i < answers.size(); ++i) {
            DNSResourceRecord answer = answers.get(i);
            if (answer.getType() != DNS.TYPE_A)
                continue;

            InetAddress ip = ((DNSRdataAddress) answer.getData()).getAddress();
            debug("check ec2 for " + ip);
            // convert ip to int
            int ipInt = ByteBuffer.wrap(ip.getAddress()).getInt();

            for (EC2 ec2 : ec2List) {
                if (ec2.match(ipInt)) {
                    debug("\t found ec2 match! region = " + ec2.region);

                    String str = ec2.region + "-" + answer.getData();
                    DNSRdataString txtString = new DNSRdataString(str);
                    DNSResourceRecord txtRecord =
                            new DNSResourceRecord(answer.getName(), DNS.TYPE_TXT, txtString);
                    dns.addAnswer(txtRecord);
                    debug("adding txt record: " + txtRecord);
                }
            }
        }
    }
}

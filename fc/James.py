import enum
import logging
import llp
import queue
import struct
import threading
import time
import sched

#What Client Do is :
#    sender = swp.SWPSender((settings.hostname, settings.port),
#            settings.loss_probability)
#    for line in sys.stdin:
#        sender.send(line.encode())


class SWPType(enum.IntEnum):
    DATA = ord('D') #unicode
    ACK = ord('A')  #unicode

class SWPPacket:
    _PACK_FORMAT = '!BI'
    _HEADER_SIZE = struct.calcsize(_PACK_FORMAT)
    MAX_DATA_SIZE = 1400 # Leaves plenty of space for IP + UDP + SWP header

    def __init__(self, type, seq_num, data=b''):
        self._type = type
        self._seq_num = seq_num
        self._data = data

    @property
    def type(self):
        return self._type

    @property
    def seq_num(self):
        return self._seq_num

    @property
    def data(self):
        return self._data

    def to_bytes(self):
        header = struct.pack(SWPPacket._PACK_FORMAT, self._type.value,
                self._seq_num)
        return header + self._data

    @classmethod
    def from_bytes(cls, raw):
        header = struct.unpack(SWPPacket._PACK_FORMAT,
                raw[:SWPPacket._HEADER_SIZE])
        type = SWPType(header[0])
        seq_num = header[1]
        data = raw[SWPPacket._HEADER_SIZE:]
        return SWPPacket(type, seq_num, data)

    def __str__(self):
        return "%s %d %s" % (self._type.name, self._seq_num, repr(self._data))

class SWPSender:
    _SEND_WINDOW_SIZE = 5
    _TIMEOUT = 1
    _LBA = -1;
    _LBS = 0;
    _AWDN = 5;
    _packet_num = 0;
    buff = dict()
    semaphore = threading.Semaphore(_SEND_WINDOW_SIZE)
    timer = sched.scheduler(time.time, time.sleep)


    def __init__(self, remote_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address,
                loss_probability=loss_probability)

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()
        #semaphore = threading.Semaphore(1)

        # TODO: Add additional state variables


    def send(self, data):
        for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
            self._send(data[i:i+SWPPacket.MAX_DATA_SIZE])

    defemaphore = threading.Semaphore(_SEND_WINDOW_SIZE)
    timer = sched.scheduler(time.time, time.sleep)

    def _send(self, data):
        # TODO
        #1.Wait for a free space in the send window—a semaphore is the simplest way to handle this.
        #2.Assign the chunk of data a sequence number—the first chunk of data is assigned sequence number 0, and the sequence number is incremented for each subsequent chunk of data.
        #3.Add the chunk of data to a buffer—in case it needs to be retransmitted.
        #4.Send the data in an SWP packet with the appropriate type (D) and sequence number—use the SWPPacket class to construct such a packet and use the send method provided by the LLPEndpoint class to transmit the packet across the network.
        #5.Start a retransmission timer—the Timer class provides a convenient way to do this; the timeout should be 1 second, defined by the constant SWPSender._TIMEOUT; when the timer expires, the _retransmit method should be called.

        l = len(data) - 1
        #SWPSender._LBS = 0
        #SWPSender._LBA = 0
        #while(l > 0):
        SWPSender.semaphore.acquire() #means SWPSender.AWND > 0

        #if SWPSender.LBS - SWPSender.LBA < SWPSender._SEND_WINDOW_SIZE:
        logging.debug("l: %d" % l)
        logging.debug("LBS: %d" % SWPSender._LBS)
        logging.debug("LBA: %s" % SWPSender._LBA)
        SWPSender._AWND = SWPSender._SEND_WINDOW_SIZE - SWPSender._LBS + SWPSender._LBA
        #awdn = SWPSender.AWND
        if(l > SWPPacket.MAX_DATA_SIZE):
            SWPSender._buff[SWPSender._LBS ] = data[SWPSender._LBS : SWPSender._LBS + SWPPacket.MAX_DATA_SIZE]
            s = SWPPacket(SWPType.DATA, SWPSender._LBS,  SWPSender._buff[SWPSender._LBS])
            SWPSender._LBS = SWPSender._LBS + SWPPacket.MAX_DATA_SIZE + 1
            l = l - SWPPacket.MAX_DATA_SIZE
        else:
            #SWPSender._buff[SWPSender._LBS] = data[SWPSender._LBS: SWPSender._LBS + l]
            #Start a new packet
            SWPSender.buff[SWPSender._LBS % SWPPacket.MAX_DATA_SIZE] = data[0:l]
            #SWPSender._LBS = SWPSender._LBS + 1
            s = SWPPacket(SWPType.DATA, SWPSender._LBS,  SWPSender.buff[SWPSender._LBS % SWPPacket.MAX_DATA_SIZE])
        packet = s.to_bytes()
        self._llp_endpoint.send(packet)
        SWPSender._retransmit(self, SWPSender._LBS)
        SWPSender._LBS = SWPSender._LBS + 1

        return

    def _retransmit(self, seq_num):
        # TODO
        s = SWPPacket(SWPType.DATA, seq_num, SWPSender.buff[seq_num % SWPPacket.MAX_DATA_SIZE])
        packet = s.to_bytes()
        while seq_num > SWPSender._LBA :

            SWPSender.timer.enter(1, 1, self._llp_endpoint.send, kwargs={'raw_bytes' : packet})
            SWPSender.timer.run()

        return

    def _recv(self):
        while True:
            # Receive SWP packet
            raw = self._llp_endpoint.recv()
            if raw is None:
                continue
            packet = SWPPacket.from_bytes(raw)
            logging.debug("My SWPSende Received: %s" % packet)

            # TODO

            SWPSender._LBA = packet._seq_num
            logging.debug("SEND_recv->LBA : %d" % SWPSender._LBA)
            SWPSender.semaphore.release()
        return

class SWPReceiver:
    _RECV_WINDOW_SIZE = 5

    def __init__(self, local_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(local_address=local_address,
                loss_probability=loss_probability)

        # Received data waiting for application to consume
        self._ready_data = queue.Queue()

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()


    def recv(self):
        return self._ready_data.get()

    def _recv(self):
        maxNumInSeq = 0
        while True:
            # Receive data packet
            raw = self._llp_endpoint.recv()
            packet = SWPPacket.from_bytes(raw)
            logging.debug("My SWPReceiver Received: %s" % packet)
            logging.debug("Seq No: %d" % packet.seq_num)
            # TODO


        return

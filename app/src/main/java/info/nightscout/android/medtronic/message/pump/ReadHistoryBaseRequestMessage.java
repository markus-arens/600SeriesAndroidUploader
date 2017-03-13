package info.nightscout.android.medtronic.message.pump;

import android.util.Log;

import org.anarres.lzo.LzoAlgorithm;
import org.anarres.lzo.LzoDecompressor;
import org.anarres.lzo.LzoInputStream;
import org.anarres.lzo.LzoLibrary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.TimeoutException;

import info.nightscout.android.USB.UsbHidDriver;
import info.nightscout.android.medtronic.MedtronicCnlSession;
import info.nightscout.android.medtronic.exception.ChecksumException;
import info.nightscout.android.medtronic.exception.EncryptionException;
import info.nightscout.android.medtronic.exception.InvalidMessageException;
import info.nightscout.android.medtronic.message.AbstractRequestMessage;
import info.nightscout.android.medtronic.message.AbstractResponseMessage;
import info.nightscout.android.medtronic.message.pump.command.multipacket.AckMultipacketCommand;
import info.nightscout.android.medtronic.message.pump.command.multipacket.InitiateTransferCommand;
import info.nightscout.android.medtronic.message.pump.command.multipacket.MultiPacketSegmentResponseMessage;
import info.nightscout.android.medtronic.message.pump.command.multipacket.RepeatSegmentPacketsCommand;
import info.nightscout.android.utils.HexDump;

import static android.R.attr.value;
import static android.R.id.message;

/**
 * Created by lgoedhart on 26/03/2016.
 */
public abstract class ReadHistoryBaseRequestMessage<T extends AbstractResponseMessage> extends MedtronicSendMessageRequestMessage<T> {
    private static final String TAG = ReadHistoryBaseRequestMessage.class.getSimpleName();
    private final LzoDecompressor lzoODecompressor;

    private long segmentSize;
    private short packetSize;
    private short lastPacketSize;
    private short packetsToFetch;
    private byte[][] segments;
    private byte historyDataType;

    protected enum HistoryDataType {
        PUMP_DATA(0x2),
        SENSOR_DATA(0x2);

        protected byte value;

        HistoryDataType(int messageType) {
            value = (byte) messageType;
        }
    }


    private boolean receviedEndHistoryCommand;
    private int bytesFetched;
    private long expectedSize = 0;

    protected ReadHistoryBaseRequestMessage(MedtronicCnlSession pumpSession, HistoryDataType historyDataType, Date from, Date to) throws EncryptionException, ChecksumException {
        super(MessageCommand.READ_HISTORY_REQUEST, pumpSession, buildPayload(historyDataType, from, to));

        this.expectedSize = expectedSize;
        this.bytesFetched = 0;
        this.receviedEndHistoryCommand = false;

        lzoODecompressor = LzoLibrary.getInstance().newDecompressor(LzoAlgorithm.LZO1X, null);
    }

    @Override
    public T send(UsbHidDriver mDevice, int sendDelay) throws TimeoutException, EncryptionException, ChecksumException, InvalidMessageException, IOException {
        sendMessage(mDevice);
        sleep(sendDelay);

        while (fetchMoreData()) {
            MultiPacketSegmentResponseMessage multiPacketSegmentResponseMessage = new MultiPacketSegmentResponseMessage(mPumpSession, readMessage(mDevice));
            switch (multiPacketSegmentResponseMessage.getComDCommand()) {
                case HIGH_SPEED_MODE_COMMAND:
                    break;
                case INITIATE_MULTIPACKET_TRANSFER:
                    this.initSession(multiPacketSegmentResponseMessage.encode());
                    // Acknowledge that we're ready to start receiving data.
                    new InitiateTransferCommand(this.mPumpSession).send(mDevice);
                    break;
                case MULTIPACKET_SEGMENT_TRANSMISSION:
                    this.processSegment(
                            multiPacketSegmentResponseMessage.getPacketNumber(),
                            multiPacketSegmentResponseMessage.getSegmentPayload(),
                            mDevice
                    );

                    break;
                case END_HISTORY_TRANSMISSION:
                    this.receviedEndHistoryCommand = true;

                    // Check that we received as much data as we were expecting.
                    if (this.bytesFetched < this.expectedSize) {
                        throw new InvalidMessageException("Got less data than expected");
                    } else {
                        // We need to read another HIGH_SPEED_MODE_COMMAND off the stack.
                        this.readMessage(mDevice);
                    }
                    break;
                default:
                    break;
            }

        }
        return null;
    }

    protected boolean fetchMoreData() {
        return !this.receviedEndHistoryCommand;
    }

    protected short lastPacketNumber() {
        return (short) (this.packetsToFetch - 1);
    }

    protected boolean segmentComplete() {
        return this.segmentCount() == this.packetsToFetch;
    }

    protected boolean retransmitNeeded() {
        return this.segments[this.packetsToFetch - 1] != null && !this.segmentComplete();
    }

    // The number of segments we've actually fetched.
    protected short segmentCount() {
        short count = 0;
        for ( byte[] segment: this.segments){
            count += (segment!=null?1:0);
        }
        return count;
    }

    protected int[] missingSegmentKeys() {
        int[] keys = new int[this.segments.length];
        int count = 0;
        for ( int i = 0; i < this.segments.length; i++) {
            byte[] segment = this.segments[i];
            if (segment==null) {
                keys[count++] = i;
            };
        }

        int[] result = new int[count];
        System.arraycopy(keys, 0, result, 0, count);
        return result;
    }
    /*
    get missingSegmentKeys() {
        return this.segments.filter(value => value === undefined).keys();
    }

    get segmentPayload() {
        return Buffer.concat(this.segments);
    }*/


    protected void initSession(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(payload.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(payload);

        this.segmentSize = buffer.getInt(0x03);
        this.packetSize = buffer.getShort(0x07);
        this.lastPacketSize = buffer.getShort(0x09);
        this.packetsToFetch = buffer.getShort(0x0B);
        this.segments = new byte[packetsToFetch + 1][];
    }

    protected void processSegment(int packetNumber, byte[] segmentPayload, UsbHidDriver mDevice)
            throws InvalidMessageException, ChecksumException, EncryptionException, IOException, TimeoutException {
        Log.d(TAG, "*** GOT A MULTIPACKET SEGMENT "+ packetNumber + ", count:" + this.segmentCount());
        Log.d(TAG, "*** PAYLOAD:" + HexDump.dumpHexString(segmentPayload));

        if (packetNumber == this.lastPacketNumber() &&
                segmentPayload.length != this.lastPacketSize) {
            throw new InvalidMessageException("Multipacket Transfer last packet size mismatch");
        } else if (packetNumber != this.lastPacketNumber() &&
                segmentPayload.length != this.packetSize) {
            throw new InvalidMessageException("Multipacket Transfer packet size mismatch");
        } else if (this.retransmitNeeded()) {
            Log.d(TAG, "*** NEED SOME PACKETS RETRANSMITTED! ***" + this.missingSegmentKeys());

            new RepeatSegmentPacketsCommand(this.mPumpSession).send(mDevice);
        } else if (this.segmentComplete()) {
            if (segmentPayload.length != this.segmentSize) {
                throw new InvalidMessageException("Total segment size mismatch");
            }

            ByteBuffer buffer = ByteBuffer.allocate(segmentPayload.length);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.put(segmentPayload);

            // multiByteSegments don't always come back in a consecutive order.
            this.segments[packetNumber] = buffer.array();

            // TODO - all of this should go into different classes...
            // Decompress the message
            if ((buffer.getInt(0x00) & 0xffffffffl) == 0x030E) {
                final byte HEADER_SIZE = 12; // TODO should be a static get.
                // It's an UnmergedHistoryUpdateCompressed response. We need to decompress it
                final byte dataType = buffer.get(0x02); // Returns a HISTORY_DATA_TYPE
                final long  historySizeCompressed = (buffer.getInt(0x03) & 0xffffffffl); //segmentPayload.readUInt32BE(0x03);
                final long historySizeUncompressed = (buffer.getInt(0x03) & 0xffffffffl); //segmentPayload.readUInt32BE(0x07);
                final boolean historyCompressed = (buffer.get(0x0B) != 0);

                if (dataType != this.historyDataType) {
                    throw new InvalidMessageException("Unexpected history type in response");
                }

                // Check that we have the correct number of bytes in this message
                if (segmentPayload.length - HEADER_SIZE != historySizeCompressed) {
                    throw new InvalidMessageException("Unexpected message size");
                }

                byte[] blockPayload;
                if (historyCompressed) {
                    byte[] lzoSegemnt;
                    System.arraycopy(segmentPayload, HEADER_SIZE, lzoSegemnt, 0, segmentPayload.length -HEADER_SIZE);
                    LzoInputStream stream = new LzoInputStream(
                            new ByteArrayInputStream(lzoSegemnt), lzoODecompressor);

                    blockPayload = new byte[stream.available()];

                    int size = stream.read(blockPayload);

                    if (size != historySizeUncompressed) {
                        Log.d(TAG, "Unexpected uncompressed message size. Expected ${historySizeUncompressed}, got ${blockPayload.length}. Original size ${historySizeCompressed}, compressed: ${historyCompressed}.");
                        throw (new InvalidMessageException("Unexpected uncompressed message size."));
                    }
                } else {
                    blockPayload = segmentPayload.slice(HEADER_SIZE);
                }

                if (blockPayload.length % ReadHistoryCommand.BLOCK_SIZE) {
                    reject(new InvalidMessageError('Block payload size is not a multiple of 2048'));
                }

                for (let i = 0; i < blockPayload.length / ReadHistoryCommand.BLOCK_SIZE; i++) {
                    const blockSize = blockPayload.readUInt16BE(
                            ((i + 1) * ReadHistoryCommand.BLOCK_SIZE) - 4);
                    const blockChecksum = blockPayload.readUInt16BE(
                            ((i + 1) * ReadHistoryCommand.BLOCK_SIZE) - 2);

                    const blockStart = i * ReadHistoryCommand.BLOCK_SIZE;
                    const blockData = blockPayload.slice(blockStart, blockStart + blockSize);
                    const calculatedChecksum =
                            NGPMessage.ccittChecksum(blockData, blockSize);
                    if (blockChecksum !== calculatedChecksum) {
                        reject(new ChecksumError(blockChecksum, calculatedChecksum,
                                `Unexpected checksum in block ${i}`));
                    } else {
                        this.blocks.push(blockData);
                    }
                }

                this.bytesFetched += blockPayload.length;
            } else {
                reject(new InvalidMessageError('Unknown history response message type'));
            }

            new AckMultipacketCommand(this.pumpSession,
                    AckMultipacketCommand.SEGMENT_COMMAND.SEND_NEXT_SEGMENT)
                    .send(hidDevice)
                    .then(() => resolve());
        }
    }

    protected static byte[] buildPayload(HistoryDataType type, Date from, Date to) {
        ByteBuffer payloadBuffer = ByteBuffer.allocate(12);
        payloadBuffer.order(ByteOrder.BIG_ENDIAN);

        payloadBuffer.put(type.value);
        payloadBuffer.put((byte) 0x04);
        payloadBuffer.putLong(from.getTime());
        payloadBuffer.putLong(to.getTime());
        payloadBuffer.putShort((short) 0x00);

        return payloadBuffer.array();
    }
}
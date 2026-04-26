// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

// BIBO wrapper that encodes a logical channel number into the CLA byte.
// Each instance represents one logical channel. Use open() to send
// MANAGE CHANNEL OPEN and get a channel BIBO, or wrap a known channel directly.
public final class LogicalChannelBIBO implements BIBO {
    private static final System.Logger logger = System.getLogger(LogicalChannelBIBO.class.getName());
    private final BIBO bibo;
    private final int channel;
    private volatile boolean closed = false;

    public LogicalChannelBIBO(BIBO bibo, int channel) {
        if (channel < 0 || channel > 19) {
            throw new IllegalArgumentException("Channel must be 0..19, got " + channel);
        }
        this.bibo = bibo;
        this.channel = channel;
    }

    // Opens a new logical channel via MANAGE CHANNEL OPEN
    public static LogicalChannelBIBO open(BIBO bibo) {
        var response = new ResponseAPDU(bibo.transceive(new byte[]{0x00, 0x70, 0x00, 0x00, 0x01}));
        if (response.getSW() != 0x9000) {
            throw new BIBOException("MANAGE CHANNEL OPEN failed: SW=%04X".formatted(response.getSW()));
        }
        var data = response.getData();
        if (data.length < 1) {
            throw new BIBOException("MANAGE CHANNEL OPEN: no channel number in response");
        }
        return new LogicalChannelBIBO(bibo, Byte.toUnsignedInt(data[0]));
    }

    public int getChannel() {
        return channel;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        if (closed) {
            throw new BIBOException("Channel " + channel + " closed");
        }
        if (channel == 0) {
            return bibo.transceive(command);
        }
        var cmd = command.clone();
        encodeChannel(cmd, channel);
        return bibo.transceive(cmd);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (channel > 0) {
            try {
                var cmd = new byte[]{0x00, 0x70, (byte) 0x80, (byte) channel};
                encodeChannel(cmd, channel);
                var response = new ResponseAPDU(bibo.transceive(cmd));
                if (response.getSW() != 0x9000) {
                    logger.log(System.Logger.Level.WARNING, "MANAGE CHANNEL CLOSE ch={0} failed: SW={1}", channel, "%04X".formatted(response.getSW()));
                }
            } catch (BIBOException e) {
                logger.log(System.Logger.Level.WARNING, "MANAGE CHANNEL CLOSE ch={0} failed: {1}", channel, e.getMessage());
            }
        }
    }

    // Encodes logical channel number into CLA byte per ISO 7816-4.
    // Matches JDK's ChannelImpl.setChannel() encoding.
    static void encodeChannel(byte[] cmd, int channel) {
        int cla = cmd[0] & 0xFF;
        if ((cla & 0b1000_0000) != 0) { // 0x80
            return; // proprietary class - don't modify
        }
        if (channel <= 3) {
            cmd[0] = (byte) ((cla & 0b1011_1100) | channel); // 0xBC
        } else {
            cmd[0] = (byte) ((cla & 0b1011_0000) | 0b0100_0000 | (channel - 4)); // 0xB0 | 0x40
        }
    }
}

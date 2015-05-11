package tftp;

import java.net.*;
import java.util.*;
import java.io.*;

public class TFTP {
	public static final int BUF_SIZE = 100;
	public static final int TFTP_PADDING = 0;
	public static final int OP_CODE_SIZE = 2;
	public static final int BLOCK_NUMBER_SIZE = 2;
	public static final int MAX_DATA_SIZE = 512;
	public static final int READ_OP_CODE = 1;
	public static final int WRITE_OP_CODE = 2;
	public static final int DATA_OP_CODE = 3;
	public static final int ACK_OP_CODE = 4;
	public static final int ERROR_OP_CODE = 5;

	/**
	 * Forms a DatagramPacket using Request r with information about request type
	 * (read, write, or test), filename, and mode (ascii, octet, etc.).
	 *
	 * @param addr InetAddress of packet destination
	 * @param port Port of packet destination
	 * @param r Request contains request type (READ or WRITE), filename, and mode
	 *
	 * @return Datagram packet for specified address and port with given request
	 */
	public static DatagramPacket formRQPacket(InetAddress addr, int port, Request r) {
		int currentIndex;
		// Create byte array for packet
		byte[] buf = new byte[BUF_SIZE];
		// First element will always be 0
		buf[0] = TFTP_PADDING;
		switch (r.getType()) {
			case READ:
				buf[1] = 1;
				break;
			case WRITE:
				buf[1] = 2;
				break;
			default:
				buf[1] = TFTP_PADDING;
				break;
		}

		// Add filename to packet data
		byte[] fbytes = r.getFilename().getBytes();
		System.arraycopy(fbytes,0,buf,OP_CODE_SIZE,fbytes.length);

		// Add 0 byte padding
		currentIndex = fbytes.length + OP_CODE_SIZE;
		buf[currentIndex] = TFTP_PADDING;
		currentIndex++;

		// Add mode to packet data
		byte[] mbytes = r.getMode().getBytes();
		System.arraycopy(mbytes,0,buf,currentIndex,mbytes.length);

		// Add terminating 0 byte
		currentIndex = currentIndex + mbytes.length;
		buf[currentIndex] = TFTP_PADDING;

		// Truncate trailing zeros by copyings to a new array
		byte[] data = new byte[currentIndex + 1];
		System.arraycopy(buf,0,data,0,currentIndex+1);

		return new DatagramPacket(data,currentIndex+1, addr, port);
	}

	/**
	 * Given a filename, returns a queue of datagram packets for that
	 * file in 512 byte blocks.
	 *
	 * @param addr InetAddress of packet destination
	 * @param port Port of packet destination
	 * @param filename Filename of file to read
	 *
	 * @return A queue of DATA packets formed from the file specificed in 512-byte chunks
	 */
	public static Queue<DatagramPacket> formDATAPackets(InetAddress addr, int port, String filename) {
		Queue<DatagramPacket> packetQueue = new ArrayDeque<DatagramPacket>();
		try {
			BufferedInputStream in = new BufferedInputStream(new FileInputStream(filename));
			byte[] data = new byte[MAX_DATA_SIZE];
			int blockNumber = 1;
			int n;

			//Read the file in 512 byte chunks and add to packet queue 
			while ((n = in.read(data)) != -1) {
				packetQueue.add(formDATAPacket(addr, port, blockNumber, data));
				blockNumber++;
			}
		} catch(Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		return packetQueue;
	}

	/**
	 * Writes a TFTP DATA packet to file.
	 *
	 * @param dataPacket A TFTP DATA packet
	 * @param filename Name of file to write to
	 */
	public static void writeDATAToFile(DatagramPacket dataPacket, String filename) {
		try {
			BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(filename));
			byte[] data = getData(dataPacket);
			out.write(data);
		} catch(Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Returns the op code of a datagram packet as an int.
	 *
	 * @param packet A TFTP DatagramPacket
	 *
	 * @return The OP code of the TFTP packet
	 */
	public static int getOpCode(DatagramPacket packet) {
		byte[] opCodeBytes = new byte[OP_CODE_SIZE];
		byte[] data = packet.getData();
		System.arraycopy(data,0,opCodeBytes,0,OP_CODE_SIZE);
		return Byte.toUnsignedInt(opCodeBytes[1]);
	}

	/**
	 * Given a DATA or ACK datagram packet, returns the block number as an int.
	 *
	 * @param packet A TFTP DATA or ACK packet
	 *
	 * @return The block number of the ACK or DATA packet
	 */
	public static int getBlockNumber(DatagramPacket packet) throws IllegalArgumentException {
		// Check that packet is either DATA or ACK
		int opCode = getOpCode(packet);
		boolean isDATA = opCode == DATA_OP_CODE;
		boolean isACK = opCode == ACK_OP_CODE;

		// If isn't DATA or ACK, throw an exception
		if (!(isDATA || isACK)) throw new IllegalArgumentException();

		// Get the block number as a byte array
		byte[] blockNumberBytes = new byte[BLOCK_NUMBER_SIZE];
		System.arraycopy(packet.getData(),OP_CODE_SIZE,blockNumberBytes,0,BLOCK_NUMBER_SIZE);
		
		return bytesToBlockNumber(blockNumberBytes);
	}

	/**
	 * Given a TFTP DATA packet, returns the data portion of the TFTP packet 
	 * as a byte array.
	 *
	 * @param packet A TFTP DATA packet
	 *
	 * @return The data portion of a DATA packet as a byte array
	 */
	public static byte[] getData(DatagramPacket packet) throws IllegalArgumentException {
		// Check that packet is DATA
		int opCode = getOpCode(packet);
		boolean isDATA = opCode == DATA_OP_CODE;

		// If packet isn't DATA, throw exception
		if (!isDATA) throw new IllegalArgumentException();

		int dataLen = packet.getLength() - OP_CODE_SIZE - BLOCK_NUMBER_SIZE;
		int dataStart = OP_CODE_SIZE + BLOCK_NUMBER_SIZE - 1;
		byte[] data = new byte[dataLen];
		System.arraycopy(packet.getData(),dataStart,data,0,dataLen);

		return data;
	}

	/**
	 * Give a block number and a byte array of data, creates a datagram packet for the
	 * given ip address and port.
	 *
	 * @param addr InetAddress of DATA packet destination
	 * @param port Port od DATA packet destination
	 * @param blockNumber The block number of the DATA packet
	 * @param data The byte array holding the data
	 *
	 * @return The respective DATA packet formed with given inputs.
	 */
	private static DatagramPacket formDATAPacket(InetAddress addr, int port, int blockNumber, byte[] data) {
		// 4+data.length because 2 bytes for op code and 2 bytes for blockNumber
		byte[] buf = new byte[OP_CODE_SIZE + BLOCK_NUMBER_SIZE +data.length];

		// Op code
		buf[0] = 0;
		buf[1] = 3;

		// Block number
		try {
			byte[] blockNumberBytes = blockNumberToBytes(blockNumber);
			System.arraycopy(blockNumberBytes,0,buf,OP_CODE_SIZE,BLOCK_NUMBER_SIZE);
		} catch(Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		// Data
		int startIndex = OP_CODE_SIZE + BLOCK_NUMBER_SIZE;
		System.arraycopy(data,0,buf,startIndex,data.length);

		return new DatagramPacket(buf,buf.length,addr,port);	
	}

	/**
	 * Converts an integer to a 2-byte byte array.
	 *
	 * @param blockNumber Integer to be coverted to a 2-byte byte array
	 *
	 * @return 2-byte representation of given block number
	 */
	public static byte[] blockNumberToBytes(int blockNumber) throws IllegalArgumentException {
		if (blockNumber<0 || blockNumber>66535) throw new IllegalArgumentException();

		byte[] blockNumberBytes = new byte[2];
		blockNumberBytes[0] = (byte) (blockNumber / 256);
		blockNumberBytes[1] = (byte) (blockNumber % 256);

		return blockNumberBytes;
	}

	/**
	 * Converts a 2-byte byte array to an integer.
	 *
	 * @param bytes 2-byte byte array holding the block number
	 *
	 * @return Int representation of given byte array
	 */
	public static int bytesToBlockNumber(byte[] bytes) throws IllegalArgumentException {
		if (bytes.length != 2) throw new IllegalArgumentException();
		int msb = Byte.toUnsignedInt(bytes[0]);
		int lsb = Byte.toUnsignedInt(bytes[1]);
		return msb*256 + lsb;
	}

	/**
	 * Forms a ACK packet for the given ip address, port and blocknumber
	 *
	 * @param addr InetAddress of DATA packet destination
	 * @param port Port od DATA packet destination
	 * @param blockNumber Block number of the ACK packet
	 *
	 * @return ACK packet formed with given inputs
	 */
	public static DatagramPacket formACKPacket(InetAddress addr, int port, int blockNumber) {
		byte[] buf = new byte[OP_CODE_SIZE + BLOCK_NUMBER_SIZE];

		// Op code
		buf[0] = 0;
		buf[1] = 4;

		// Block number
		try {
			byte[] blockNumberBytes = blockNumberToBytes(blockNumber);
			System.arraycopy(blockNumberBytes,0,buf,OP_CODE_SIZE,BLOCK_NUMBER_SIZE);
		} catch(Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		return new DatagramPacket(buf,buf.length,addr,port);
	}

	public static DatagramPacket formERRORPacket(InetAddress addr, int port, int errorCode, String errMsg) {
		return null;	
	}

	/**
	 * Parse a given DatagramPacket p to see if it is valid. A valid packet must begin
	* with [0,1] or [0,2], followed by an arbitrary number of bytes representing the 
	* filename, followed by a 0 byte, followed by an arbitrary number of bytes representing
	* the mode, followed by a terminating 0 byte.
	* If the packet is valid, a request with the respective request type, filename, and mode
	* is created. Otherwise, an exception is thrown and the server quits.
	*
	* @param p Datagram packet to be parsed. Must either be a RRQ or WRQ packet.
	*
	* @return Request of the packet.
	*/
	public Request parseRQ(DatagramPacket p) throws IllegalArgumentException {
		Request.Type t;
		String f, m;
		int currentIndex = 0;

		// Get number of bytes used by packet data
		int len = p.getData().length; 
		// Make copy of data bytes to parse
		byte[] buf = new byte[len];
		System.arraycopy(p.getData(),0,buf,0,len);

		// If first byte isn't 0, packet is invalid
		if (buf[0] != TFTP_PADDING) throw new IllegalArgumentException();

		// Check second byte for read or write
		switch (buf[1]) {
			case 1:
				t = Request.Type.READ;
				break;
			case 2:
				t = Request.Type.WRITE;
				break;
			default:
				throw new IllegalArgumentException();
		}

		// Get filename
		currentIndex = 2;
		if (currentIndex >= len) throw new IllegalArgumentException();
		// Create an array of bytes to hold filename byte data
		byte[] fbytes = new byte[len];
		// Loop through array until 0 byte is found or out of bound occurs
		while (buf[currentIndex] != TFTP_PADDING) {
			currentIndex++;
			if (currentIndex >= len) throw new IllegalArgumentException();
		}
		int filenameLength = currentIndex - 2;
		System.arraycopy(buf,2,fbytes,0,filenameLength);
		f = new String(fbytes);

		// Check for 0 byte padding between filename and mode
		if (buf[currentIndex] != TFTP_PADDING) throw new IllegalArgumentException();

		// Get mode
		currentIndex++;
		if (currentIndex >= len) throw new IllegalArgumentException();
		int modeStartIndex = currentIndex;
		byte[] mbytes = new byte[len];
		// Loop through array until 0 byte is found or out of bound occurs
		while (buf[currentIndex] != TFTP_PADDING) {
			currentIndex++;
			if (currentIndex >= len) throw new IllegalArgumentException();
		}
		int modeLength = currentIndex - modeStartIndex;
		System.arraycopy(buf,modeStartIndex,mbytes,0,modeLength);
		m = new String(mbytes);

		return new Request(t, f, m);
	}
}

/** Copyright (C) 2011 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.client;

import com.turn.ttorrent.client.Message;
import com.turn.ttorrent.client.Message;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.BitSet;

/** BitTorrent peer protocol messages representations.
 *
 * <p>
 * This class and its <em>*Messages</em> subclasses provide POJO
 * representations of the peer protocol messages, along with easy parsing from
 * an input ByteBuffer to quickly get a usable representation of an incoming
 * message.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Peer_wire_protocol_.28TCP.29">BitTorrent peer wire protocol</a>
 */
public abstract class Message {

	/** Message type.
	 *
	 * Note that the keep-alive messages don't actually have an type ID defined
	 * in the protocol as they are of length 0.
	 */
	public enum Type {
		KEEP_ALIVE(-1),
		CHOKE(0),
		UNCHOKE(1),
		INTERESTED(2),
		NOT_INTERESTED(3),
		HAVE(4),
		BITFIELD(5),
		REQUEST(6),
		PIECE(7),
		CANCEL(8);

		private int id;
		Type(int id) {
			this.id = id;
		}

		public boolean equals(char c) {
			return this.id == c;
		}

		public byte getTypeByte() {
			return (byte)this.id;
		}

		public static Type get(char c) {
			for (Type t : Type.values()) {
				if (t.equals(c)) {
					return t;
				}
			}
			return null;
		}
	};

	private Type type;
	private ByteBuffer data;

	private Message(Type type, ByteBuffer data) {
		this.type = type;
		this.data = data;
		this.data.rewind();
	}

	public Type getType() {
		return this.type;
	}

	public ByteBuffer getData() {
		return this.data;
	}

	/** Validate that this message makes sense for the torrent it's related to.
	 *
	 * This method is meant to be overloaded by distinct message types, where
	 * it makes sense. Otherwise, it defaults to true.
	 *
	 * @param torrent The torrent this message is about.
	 */
	public Message validate(SharedTorrent torrent)
		throws MessageValidationException {
		return this;
	}

	public String toString() {
		return this.getType().name();
	}

	/** Parse the given buffer into a peer protocol Message.
	 *
	 * Parses the provided byte array and builds the corresponding Message
	 * subclass object.
	 *
	 * @param buffer The byte buffer containing the message data.
	 * @param torrent The torrent this message is about.
	 * @return A Message subclass instance.
	 * @throws ParseException When the message is invalid, can't be parsed or
	 * does not match the protocol requirements.
	 */
	public static Message parse(ByteBuffer buffer, SharedTorrent torrent)
		throws ParseException {
		buffer.rewind();

		int length = buffer.getInt();
		if (length == 0) {
			return KeepAliveMessage.parse(buffer, torrent);
		} else if (length != buffer.remaining()) {
			throw new ParseException("Message size did not match announced " +
					"size!", 0);
		}

		Type type = Type.get((char)buffer.get());
		if (type == null) {
			throw new ParseException("Unknown message ID!",
					buffer.position()-1);
		}

		switch (type) {
			case CHOKE:
				return ChokeMessage.parse(buffer.slice(), torrent);
			case UNCHOKE:
				return UnchokeMessage.parse(buffer.slice(), torrent);
			case INTERESTED:
				return InterestedMessage.parse(buffer.slice(), torrent);
			case NOT_INTERESTED:
				return NotInterestedMessage.parse(buffer.slice(), torrent);
			case HAVE:
				return HaveMessage.parse(buffer.slice(), torrent);
			case BITFIELD:
				return BitfieldMessage.parse(buffer.slice(), torrent);
			case REQUEST:
				return RequestMessage.parse(buffer.slice(), torrent);
			case PIECE:
				return PieceMessage.parse(buffer.slice(), torrent);
			case CANCEL:
				return CancelMessage.parse(buffer.slice(), torrent);
			default:
				throw new IllegalStateException("Message type should have " +
						"been properly defined by now.");
		}
	}

	public static class MessageValidationException extends ParseException {

		static final long serialVersionUID = -1;

		public MessageValidationException(Message m) {
			super("Message " + m + " is not valid!", 0);
		}

	}


	/** Keep alive message.
	 *
	 * <len=0000>
	 */
	public static class KeepAliveMessage extends Message {

		private static final int BASE_SIZE = 0;

		private KeepAliveMessage(ByteBuffer buffer) {
			super(Type.KEEP_ALIVE, buffer);
		}

		public static KeepAliveMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			return (KeepAliveMessage)new KeepAliveMessage(buffer)
				.validate(torrent);
		}

		public static KeepAliveMessage craft() {
			return new KeepAliveMessage(ByteBuffer.allocate(
						KeepAliveMessage.BASE_SIZE));
		}
	}

	/** Choke message.
	 *
	 * <len=0001><id=0>
	 */
	public static class ChokeMessage extends Message {

		private static final int BASE_SIZE = 1;

		private ChokeMessage(ByteBuffer buffer) {
			super(Type.CHOKE, buffer);
		}

		public static ChokeMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			return (ChokeMessage)new ChokeMessage(buffer)
				.validate(torrent);
		}

		public static ChokeMessage craft() {
			ByteBuffer buffer = ByteBuffer.allocate(ChokeMessage.BASE_SIZE + 4);
			buffer.putInt(ChokeMessage.BASE_SIZE);
			buffer.put(Message.Type.CHOKE.getTypeByte());
			return new ChokeMessage(buffer);
		}
	}

	/** Unchoke message.
	 *
	 * <len=0001><id=1>
	 */
	public static class UnchokeMessage extends Message {

		private static final int BASE_SIZE = 1;

		private UnchokeMessage(ByteBuffer buffer) {
			super(Type.UNCHOKE, buffer);
		}

		public static UnchokeMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			return (UnchokeMessage)new UnchokeMessage(buffer)
				.validate(torrent);
		}

		public static UnchokeMessage craft() {
			ByteBuffer buffer = ByteBuffer.allocate(UnchokeMessage.BASE_SIZE + 4);
			buffer.putInt(UnchokeMessage.BASE_SIZE);
			buffer.put(Message.Type.UNCHOKE.getTypeByte());
			return new UnchokeMessage(buffer);
		}
	}

	/** Interested message.
	 *
	 * <len=0001><id=2>
	 */
	public static class InterestedMessage extends Message {

		private static final int BASE_SIZE = 1;

		private InterestedMessage(ByteBuffer buffer) {
			super(Type.INTERESTED, buffer);
		}

		public static InterestedMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			return (InterestedMessage)new InterestedMessage(buffer)
				.validate(torrent);
		}

		public static InterestedMessage craft() {
			ByteBuffer buffer = ByteBuffer.allocate(InterestedMessage.BASE_SIZE + 4);
			buffer.putInt(InterestedMessage.BASE_SIZE);
			buffer.put(Message.Type.INTERESTED.getTypeByte());
			return new InterestedMessage(buffer);
		}
	}

	/** Not interested message.
	 *
	 * <len=0001><id=3>
	 */
	public static class NotInterestedMessage extends Message {

		private static final int BASE_SIZE = 1;

		private NotInterestedMessage(ByteBuffer buffer) {
			super(Type.NOT_INTERESTED, buffer);
		}

		public static NotInterestedMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			return (NotInterestedMessage)new NotInterestedMessage(buffer)
				.validate(torrent);
		}

		public static NotInterestedMessage craft() {
			ByteBuffer buffer = ByteBuffer.allocate(NotInterestedMessage.BASE_SIZE + 4);
			buffer.putInt(NotInterestedMessage.BASE_SIZE);
			buffer.put(Message.Type.NOT_INTERESTED.getTypeByte());
			return new NotInterestedMessage(buffer);
		}
	}

	/** Have message.
	 *
	 * <len=0005><id=4><piece index=xxxx>
	 */
	public static class HaveMessage extends Message {

		private static final int BASE_SIZE = 5;

		private int piece;

		private HaveMessage(ByteBuffer buffer, int piece) {
			super(Type.HAVE, buffer);
			this.piece = piece;
		}

		public int getPieceIndex() {
			return this.piece;
		}

		@Override
		public HaveMessage validate(SharedTorrent torrent)
			throws MessageValidationException {
			if (this.piece >= 0 && this.piece < torrent.getPieceCount()) {
				return this;
			}

			throw new MessageValidationException(this);
		}

		public static HaveMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			return new HaveMessage(buffer, buffer.getInt())
				.validate(torrent);
		}

		public static HaveMessage craft(int piece) {
			ByteBuffer buffer = ByteBuffer.allocate(HaveMessage.BASE_SIZE + 4);
			buffer.putInt(HaveMessage.BASE_SIZE);
			buffer.put(Message.Type.HAVE.getTypeByte());
			buffer.putInt(piece);
			return new HaveMessage(buffer, piece);
		}

		public String toString() {
			return super.toString() + " #" + this.getPieceIndex();
		}
	}

	/** Bitfield message.
	 *
	 * <len=0001+X><id=5><bitfield>
	 */
	public static class BitfieldMessage extends Message {

		private static final int BASE_SIZE = 1;

		private BitSet bitfield;

		private BitfieldMessage(ByteBuffer buffer, BitSet bitfield) {
			super(Type.BITFIELD, buffer);
			this.bitfield = bitfield;
		}

		public BitSet getBitfield() {
			return this.bitfield;
		}

		@Override
		public BitfieldMessage validate(SharedTorrent torrent)
			throws MessageValidationException {
			if (this.bitfield.length() <= torrent.getPieceCount()) {
				return this;
			}

			throw new MessageValidationException(this);
		}

		public static BitfieldMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			BitSet bitfield = new BitSet(buffer.remaining()*8);
			for (int i=0; i < buffer.remaining()*8; i++) {
				if ((buffer.get(i/8) & (1 << (7 -(i % 8)))) > 0) {
					bitfield.set(i);
				}
			}

			return new BitfieldMessage(buffer, bitfield)
				.validate(torrent);
		}

		public static BitfieldMessage craft(BitSet availablePieces) {
			byte[] bitfield = new byte[availablePieces.length()/8 + 1];
			for (int i=availablePieces.nextSetBit(0); i >= 0;
					i=availablePieces.nextSetBit(i+1)) {
				bitfield[i/8] |= 1 << (7 -(i % 8));
			}

			ByteBuffer buffer = ByteBuffer.allocate(BitfieldMessage.BASE_SIZE +
					4 + bitfield.length);
			buffer.putInt(BitfieldMessage.BASE_SIZE + bitfield.length);
			buffer.put(Message.Type.BITFIELD.getTypeByte());
			buffer.put(ByteBuffer.wrap(bitfield));
			return new BitfieldMessage(buffer, availablePieces);
		}

		public String toString() {
			return super.toString() + " " + this.getBitfield().cardinality();
		}
	}

	/** Request message.
	 *
	 * <len=00013><id=6><piece index><block offset><block length>
	 */
	public static class RequestMessage extends Message {

		private static final int BASE_SIZE = 13;

		/** Default block size is 2^14 bytes, or 16kB. */
		public static final int DEFAULT_REQUEST_SIZE = 16384;

		/** Max block request size is 2^17 bytes, or 131kB. */
		public static final int MAX_REQUEST_SIZE = 131072;

		private int piece;
		private int offset;
		private int length;

		private RequestMessage(ByteBuffer buffer, int piece,
				int offset, int length) {
			super(Type.REQUEST, buffer);
			this.piece = piece;
			this.offset = offset;
			this.length = length;
		}

		public int getPiece() {
			return this.piece;
		}

		public int getOffset() {
			return this.offset;
		}

		public int getLength() {
			return this.length;
		}

		@Override
		public RequestMessage validate(SharedTorrent torrent)
			throws MessageValidationException {
			if (this.piece >= 0 && this.piece < torrent.getPieceCount() &&
				this.offset + this.length <=
					torrent.getPiece(this.piece).size()) {
				return this;
			}

			throw new MessageValidationException(this);
		}

		public static RequestMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			int piece = buffer.getInt();
			int offset = buffer.getInt();
			int length = buffer.getInt();
			return new RequestMessage(buffer, piece,
					offset, length).validate(torrent);
		}

		public static RequestMessage craft(int piece, int offset, int length) {
			ByteBuffer buffer = ByteBuffer.allocate(RequestMessage.BASE_SIZE + 4);
			buffer.putInt(RequestMessage.BASE_SIZE);
			buffer.put(Message.Type.REQUEST.getTypeByte());
			buffer.putInt(piece);
			buffer.putInt(offset);
			buffer.putInt(length);
			return new RequestMessage(buffer, piece, offset, length);
		}

		public String toString() {
			return super.toString() + " #" + this.getPiece() +
				" (" + this.getLength() + "@" + this.getOffset() + ")";
		}
	}

	/** Piece message.
	 *
	 * <len=0009+X><id=7><piece index><block offset><block data>
	 */
	public static class PieceMessage extends Message {

		private static final int BASE_SIZE = 9;

		private int piece;
		private int offset;
		private ByteBuffer block;

		private PieceMessage(ByteBuffer buffer, int piece,
				int offset, ByteBuffer block) {
			super(Type.PIECE, buffer);
			this.piece = piece;
			this.offset = offset;
			this.block = block;
		}

		public int getPiece() {
			return this.piece;
		}

		public int getOffset() {
			return this.offset;
		}

		public ByteBuffer getBlock() {
			return this.block;
		}

		@Override
		public PieceMessage validate(SharedTorrent torrent)
			throws MessageValidationException {
			if (this.piece >= 0 && this.piece < torrent.getPieceCount() &&
				this.offset + this.block.limit() <=
				torrent.getPiece(this.piece).size()) {
				return this;
			}

			throw new MessageValidationException(this);
		}

		public static PieceMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			int piece = buffer.getInt();
			int offset = buffer.getInt();
			ByteBuffer block = buffer.slice();
			return new PieceMessage(buffer, piece, offset, block)
				.validate(torrent);
		}

		public static PieceMessage craft(int piece, int offset,
				ByteBuffer block) {
			ByteBuffer buffer = ByteBuffer.allocate(PieceMessage.BASE_SIZE +
					4 + block.capacity());
			buffer.putInt(PieceMessage.BASE_SIZE + block.capacity());
			buffer.put(Message.Type.PIECE.getTypeByte());
			buffer.putInt(piece);
			buffer.putInt(offset);
			buffer.put(block);
			return new PieceMessage(buffer, piece, offset, block);
		}

		public String toString() {
			return super.toString() + " #" + this.getPiece() +
				" (" + this.getBlock().capacity() + "@" + this.getOffset() + ")";
		}
	}

	/** Cancel message.
	 *
	 * <len=00013><id=8><piece index><block offset><block length>
	 */
	public static class CancelMessage extends Message {

		private static final int BASE_SIZE = 13;

		private int piece;
		private int offset;
		private int length;

		private CancelMessage(ByteBuffer buffer, int piece,
				int offset, int length) {
			super(Type.CANCEL, buffer);
			this.piece = piece;
			this.offset = offset;
			this.length = length;
		}

		public int getPiece() {
			return this.piece;
		}

		public int getOffset() {
			return this.offset;
		}

		public int getLength() {
			return this.length;
		}

		@Override
		public CancelMessage validate(SharedTorrent torrent)
			throws MessageValidationException {
			if (this.piece >= 0 && this.piece < torrent.getPieceCount() &&
				this.offset + this.length <=
					torrent.getPiece(this.piece).size()) {
				return this;
			}

			throw new MessageValidationException(this);
		}

		public static CancelMessage parse(ByteBuffer buffer,
				SharedTorrent torrent) throws MessageValidationException {
			int piece = buffer.getInt();
			int offset = buffer.getInt();
			int length = buffer.getInt();
			return new CancelMessage(buffer, piece,
					offset, length).validate(torrent);
		}

		public static CancelMessage craft(int piece, int offset, int length) {
			ByteBuffer buffer = ByteBuffer.allocate(CancelMessage.BASE_SIZE+4);
			buffer.putInt(CancelMessage.BASE_SIZE);
			buffer.put(Message.Type.CANCEL.getTypeByte());
			buffer.putInt(piece);
			buffer.putInt(offset);
			buffer.putInt(length);
			return new CancelMessage(buffer, piece, offset, length);
		}

		public String toString() {
			return super.toString() + " #" + this.getPiece() +
				" (" + this.getLength() + "@" + this.getOffset() + ")";
		}
	}
}

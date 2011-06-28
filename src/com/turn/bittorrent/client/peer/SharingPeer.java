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

package com.turn.bittorrent.client.peer;

import com.turn.bittorrent.common.Peer;
import com.turn.bittorrent.client.Message;
import com.turn.bittorrent.client.Piece;
import com.turn.bittorrent.client.SharedTorrent;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

/** A peer exchanging on a torrent with the BitTorrent client.
 *
 * <p>
 * A SharingPeer extends the base Peer class with all the data and logic needed
 * by the BitTorrent client to interact with a peer exchanging on the same
 * torrent.
 * </p>
 *
 * <p>
 * Peers are defined by their peer ID, IP address and port number, just like
 * base peers. Peers we exchange with also contain four crucial attributes:
 * </p>
 *
 * <ul>
 *   <li><code>choking</code>, which means we are choking this peer and we're
 *   not willing to send him anything for now;</li>
 *   <li><code>interesting</code>, which means we are interested in a piece
 *   this peer has;</li>
 *   <li><code>choked</code>, if this peer is choking and won't send us
 *   anything right now;</li>
 *   <li><code>interested</code>, if this peer is interested in something we
 *   have.</li>
 * </ul>
 *
 * <p>
 * Peers start choked and uninterested.
 * </p>
 *
 * @author mpetazzoni
 */
public class SharingPeer extends Peer implements MessageListener {

	private static final Logger logger = Logger.getLogger(SharingPeer.class);

	private static final int MAX_PIPELINED_REQUESTS = 5;

	private boolean choking;
	private boolean interesting;

	private boolean choked;
	private boolean interested;

	private SharedTorrent torrent;
	private BitSet availablePieces;

	private Piece requestedPiece;
	private int lastRequestedOffset;
	private BlockingQueue<Message.RequestMessage> requests;

	private PeerExchange exchange;
	private Object exchangeLock;
	private Rate download;
	private Rate upload;

	private Set<PeerActivityListener> listeners;

	/** Create a new sharing peer on a given torrent.
	 *
	 * @param ip The peer's IP address.
	 * @param port The peer's port.
	 * @param peerId The byte-encoded peer ID.
	 * @param torrent The torrent this peer exchanges with us on.
	 */
	public SharingPeer(String ip, int port, ByteBuffer peerId,
			SharedTorrent torrent) {
		super(ip, port, peerId);

		this.torrent = torrent;
		this.listeners = new HashSet<PeerActivityListener>();
		this.availablePieces = new BitSet(this.torrent.getPieceCount());
		this.exchangeLock = new Object();

		this.reset();
		this.requestedPiece = null;
	}

	/** Register a new peer activity listener.
	 *
	 * @param listener The activity listener that wants to receive events from
	 * this peer's activity.
	 */
	public void register(PeerActivityListener listener) {
		this.listeners.add(listener);
	}

	public Rate getDLRate() {
		return this.download;
	}

	public Rate getULRate() {
		return this.upload;
	}

	/** Reset the peer state.
	 *
	 * Initially, peers are considered choked, choking, and neither interested
	 * nor interesting.
	 */
	public synchronized void reset() {
		this.choking = true;
		this.interesting = false;
		this.choked = true;
		this.interested = false;

		this.exchange = null;

		this.requests = null;
		this.lastRequestedOffset = 0;
	}

	/** Choke this peer.
	 *
	 * We don't want to upload to this peer anymore, so mark that we're choking
	 * from this peer.
	 */
	public void choke() {
		if (!this.choking) {
			logger.trace("Choking " + this);
			this.send(Message.ChokeMessage.craft());
			this.choking = true;
		}
	}

	/** Unchoke this peer.
	 *
	 * Mark that we are no longer choking from this peer and can resume
	 * uploading to it.
	 */
	public void unchoke() {
		if (this.choking) {
			logger.trace("Unchoking " + this);
			this.send(Message.UnchokeMessage.craft());
			this.choking = false;
		}
	}

	public boolean isChoking() {
		return this.choking;
	}


	public void interesting() {
		if (!this.interesting) {
			logger.trace("Telling " + this + " we're interested.");
			this.send(Message.InterestedMessage.craft());
			this.interesting = true;
		}
	}

	public void notInteresting() {
		if (this.interesting) {
			logger.trace("Telling " + this + " we're no longer interested.");
			this.send(Message.NotInterestedMessage.craft());
			this.interesting = false;
		}
	}

	public boolean isInteresting() {
		return this.interesting;
	}


	public boolean isChoked() {
		return this.choked;
	}

	public boolean isInterested() {
		return this.interested;
	}

	/** Returns the available pieces from this peer.
	 *
	 * @return A clone of the available pieces bitfield from this peeer.
	 */
	public BitSet getAvailablePieces() {
		synchronized (this.availablePieces) {
			return (BitSet)this.availablePieces.clone();
		}
	}

	/** Returns the currently requested piece, if any.
	 */
	public Piece getRequestedPiece() {
		return this.requestedPiece;
	}

	/** Tells whether this peer is a seed.
	 *
	 * A peer is a seed if it has all of the torrent's pieces available.
	 */
	public synchronized boolean isSeed() {
		return this.torrent.getPieceCount() > 0 &&
			this.getAvailablePieces().cardinality() ==
				this.torrent.getPieceCount();
	}

	/** Bind a connected socket to this peer.
	 *
	 * This will create a new peer exchange with this peer using the given
	 * socket, and register the peer as a message listener.
	 *
	 * @param socket The connected socket for this peer.
	 */
	public synchronized void bind(Socket socket) {
		this.exchange = new PeerExchange(this, this.torrent, socket);
		this.exchange.register(this);

		this.download = new Rate();
		this.download.reset();

		this.upload = new Rate();
		this.upload.reset();
	}

	/** Tells whether this peer as an active connection through a peer
	 * exchange.
	 */
	public boolean isBound() {
		synchronized (this.exchangeLock) {
			return this.exchange != null && this.exchange.isConnected();
		}
	}

	/** Unbind and disconnect this peer.
	 *
	 * This terminates the eventually present and/or connected peer exchange
	 * with the peer and fires the peer disconnected event to any peer activity
	 * listeners registered on this peer.
	 *
	 * @param force Force unbind without sending cancel requests.
	 */
	public void unbind(boolean force) {
		if (!force) {
			// Cancel all outgoing requests, and send a NOT_INTERESTED message to
			// the peer.
			this.cancelPendingRequests();
			this.send(Message.NotInterestedMessage.craft());
		}

		synchronized (this.exchangeLock) {
			if (this.exchange != null) {
				if (force) {
					this.exchange.terminate();
				} else {
					this.exchange.close();
				}

				this.exchange = null;
			}
		}

		this.firePeerDisconnected();
		this.requestedPiece = null;
	}

	/** Send a message to the peer.
	 *
	 * Delivery of the message can only happen if the peer is connected.
	 *
	 * @param message The message to send to the remote peer through our peer
	 * exchange.
	 */
	public void send(Message message) throws IllegalStateException {
		synchronized (this.exchangeLock) {
			if (this.isBound()) {
				this.exchange.send(message);
			}
		}
	}

	/** Download the given piece from this peer.
	 *
	 * Starts a block request queue and pre-fill it with MAX_PIPELINED_REQUESTS
	 * block requests.
	 *
	 * Further requests will be added, one by one, every time a block is
	 * returned.
	 *
	 * @param piece The piece chosen to be downloaded from this peer.
	 */
	public synchronized void downloadPiece(Piece piece)
		throws IllegalStateException {
		if (this.isDownloading()) {
			IllegalStateException up = new IllegalStateException(
					"Trying to download a piece while previous " +
					"download not completed!");
			logger.warn(up);
			throw up; // ah ah.
		}

		this.requests = new LinkedBlockingQueue<Message.RequestMessage>(
				SharingPeer.MAX_PIPELINED_REQUESTS);
		this.requestedPiece = piece;
		this.lastRequestedOffset = 0;
		this.requestNextBlocks();
	}

	public synchronized boolean isDownloading() {
		return this.requests != null && this.requests.size() > 0;
	}

	/** Request some more blocks from this peer.
	 *
	 * Re-fill the pipeline to get download the next blocks from the peer.
	 */
	private synchronized void requestNextBlocks() {
		if (this.requests == null || this.requestedPiece == null) {
			throw new IllegalStateException("Trying to request blocks out " +
					"of a piece download context!");
		}

		while (this.requests.remainingCapacity() > 0 &&
				this.lastRequestedOffset < this.requestedPiece.size()) {
			Message.RequestMessage request = Message.RequestMessage.craft(
					this.requestedPiece.getIndex(),
					this.lastRequestedOffset,
					Math.min(this.requestedPiece.size() -
							this.lastRequestedOffset,
						Message.RequestMessage.DEFAULT_REQUEST_SIZE));
			this.requests.add(request);
			this.send(request);
			this.lastRequestedOffset += request.getLength();
		}
	}

	/** Remove the REQUEST message from the request pipeline matching this
	 * PIECE message.
	 *
	 * Upon reception of a piece block with a PIECE message, remove the
	 * corresponding request from the pipeline to make room for the next block
	 * requests.
	 *
	 * @param message The PIECE message received.
	 */
	private synchronized void removeBlockRequest(Message.PieceMessage message) {
		for (Message.RequestMessage request : this.requests) {
			if (request.getPiece() == message.getPiece() &&
					request.getOffset() == message.getOffset()) {
				this.requests.remove(request);
				break;
			}
		}
	}

	/** Cancel all pending requests.
	 *
	 * This queues CANCEL messages for all the requests in the queue, and
	 * returns the list of requests that were in the queue.
	 *
	 * If no request queue existed, or if it was empty, an empty set of request
	 * messages is returned.
	 */
	private synchronized Set<Message.RequestMessage> cancelPendingRequests() {
		Set<Message.RequestMessage> requests =
			new HashSet<Message.RequestMessage>();

		if (this.requests != null) {
			for (Message.RequestMessage request : this.requests) {
				this.send(Message.CancelMessage.craft(request.getPiece(),
							request.getOffset(), request.getLength()));
				requests.add(request);
			}
		}

		return requests;
	}

	/** Handle an incoming message from this peer.
	 *
	 * @param msg The incoming, parsed message.
	 */
	@Override
	public synchronized void handleMessage(Message msg) {
		switch (msg.getType()) {
			case KEEP_ALIVE:
				// Nothing to do, we're keeping the connection open anyways.
				break;
			case CHOKE:
				this.choked = true;
				this.firePeerChoked();
				this.cancelPendingRequests();
				break;
			case UNCHOKE:
				this.choked = false;
				logger.trace("Peer " + this + " is now accepting requests.");
				this.firePeerReady();
				break;
			case INTERESTED:
				this.interested = true;
				break;
			case NOT_INTERESTED:
				this.interested = false;
				break;
			case HAVE:
				// Record this peer has the given piece
				Message.HaveMessage have = (Message.HaveMessage)msg;
				Piece havePiece = this.torrent.getPiece(have.getPieceIndex());

				synchronized (this.availablePieces) {
					this.availablePieces.set(havePiece.getIndex());
					logger.trace("Peer " + this + " now has " + havePiece +
							" [" + this.availablePieces.cardinality() + "/" +
							this.torrent.getPieceCount() + "].");
				}

				this.firePieceAvailabity(havePiece);
				break;
			case BITFIELD:
				// Augment the hasPiece bitfield from this BITFIELD message
				Message.BitfieldMessage bitfield = (Message.BitfieldMessage)msg;

				synchronized (this.availablePieces) {
					this.availablePieces = bitfield.getBitfield();
					logger.trace("Recorded bitfield from " + this + " with " +
							bitfield.getBitfield().cardinality() + " piece(s) [" +
							this.availablePieces.cardinality() + "/" +
							this.torrent.getPieceCount() + "].");
				}

				this.fireBitfieldAvailabity();
				break;
			case REQUEST:
				Message.RequestMessage request = (Message.RequestMessage)msg;

				// If we are choking from this peer and it still sends us
				// requests, it is a violation of the BitTorrent protocol.
				// Similarly, if the peer requests a piece we don't have, it
				// is a violation of the BitTorrent protocol. In these
				// situation, terminate the connection.
				if (this.isChoking() ||
						!this.torrent.getPiece(request.getPiece()).isValid()) {
					logger.info("Peer " + this + " violated protocol, " +
							"terminating exchange.");
					this.unbind(true);
					break;
				}

				if (request.getLength() >
						Message.RequestMessage.MAX_REQUEST_SIZE) {
					logger.info("Peer " + this + " requested a block too big, " +
							"terminating exchange.");
					this.unbind(true);
					break;
				}

				// At this point we agree to send the requested piece block to
				// the remote peer, so let's queue a message with that block
				try {
					Piece p = this.torrent.getPiece(request.getPiece());

					ByteBuffer block = p.read(request.getOffset(),
									request.getLength());
					this.send(Message.PieceMessage.craft(request.getPiece(),
								request.getOffset(), block));
					this.upload.add(block.capacity());

					if (request.getOffset() + request.getLength() == p.size()) {
						this.firePieceSent(p);
					}
				} catch (IOException ioe) {
					this.fireIOException(ioe);
				}

				break;
			case PIECE:
				// Record the incoming piece block.

				// Should we keep track of the requested pieces and act when we
				// get a piece we didn't ask for, or should we just stay
				// greedy?
				Message.PieceMessage piece = (Message.PieceMessage)msg;
				Piece p = this.torrent.getPiece(piece.getPiece());

				// Remove the corresponding request from the request to make
				// room for next block requests.
				this.removeBlockRequest(piece);
				this.download.add(piece.getBlock().capacity());

				try {
					p.record(piece.getBlock(), piece.getOffset());

					// If the block offset equals the piece size and the block
					// length is 0, it means the piece has been entirely
					// downloaded. In this case, we have nothing to save, but
					// we should validate the piece.
					if (piece.getOffset() + piece.getBlock().capacity()
							== p.size()) {
						p.validate();
						this.firePieceCompleted(p);
						this.requestedPiece = null;
						this.firePeerReady();
					} else {
						this.requestNextBlocks();
					}
				} catch (IOException ioe) {
					this.fireIOException(ioe);
					break;
				}
				break;
			case CANCEL:
				// No need to support
				break;
		}
	}

	/** Fire the peer choked event to all registered listeners.
	 *
	 * The event contains the peer that chocked.
	 */
	private void firePeerChoked() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePeerChoked(this);
		}
	}

	/** Fire the peer ready event to all registered listeners.
	 *
	 * The event contains the peer that unchoked or became ready.
	 */
	private void firePeerReady() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePeerReady(this);
		}
	}

	/** Fire the piece availability event to all registered listeners.
	 *
	 * The event contains the peer (this), and the piece that became available.
	 */
	private void firePieceAvailabity(Piece piece) {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePieceAvailability(this, piece);
		}
	}

	/** Fire the bitfield availability event to all registered listeners.
	 *
	 * The event contains the peer (this), and the bitfield of available pieces
	 * from this peer.
	 */
	private void fireBitfieldAvailabity() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handleBitfieldAvailability(this,
					this.getAvailablePieces());
		}
	}

	/** Fire the piece sent event to all registered listeners.
	 *
	 * The event contains the peer (this), and the piece number that was
	 * sent to the peer.
	 *
	 * @param piece The completed piece.
	 */
	private void firePieceSent(Piece piece) {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePieceSent(this, piece);
		}
	}

	/** Fire the piece completion event to all registered listeners.
	 *
	 * The event contains the peer (this), and the piece number that was
	 * completed.
	 *
	 * @param piece The completed piece.
	 */
	private void firePieceCompleted(Piece piece) {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePieceCompleted(this, piece);
		}
	}

	/** Fire the peer disconnected event to all registered listeners.
	 *
	 * The event contains the peer that disconnected (this).
	 */
	private void firePeerDisconnected() {
		for (PeerActivityListener listener : this.listeners) {
			listener.handlePeerDisconnected(this);
		}
	}

	/** Fire the IOException event to all registered listeners.
	 *
	 * The event contains the peer that triggered the problem, and the
	 * exception object.
	 */
	private void fireIOException(IOException ioe) {
		for (PeerActivityListener listener : this.listeners) {
			listener.handleIOException(this, ioe);
		}
	}

	/** Download rate comparator.
	 *
	 * Compares sharing peers based on their current download rate.
	 *
	 * @author mpetazzoni
	 * @see Rate.RateComparator
	 */
	public static class DLRateComparator implements Comparator<SharingPeer> {
		@Override
		public int compare(SharingPeer a, SharingPeer b) {
			return new Rate.RateComparator()
				.compare(a.getDLRate(), b.getDLRate());
		}
	}

	/** Upload rate comparator.
	 *
	 * Compares sharing peers based on their current upload rate.
	 *
	 * @author mpetazzoni
	 * @see Rate.RateComparator
	 */
	public static class ULRateComparator implements Comparator<SharingPeer> {
		@Override
		public int compare(SharingPeer a, SharingPeer b) {
			return new Rate.RateComparator()
				.compare(a.getULRate(), b.getULRate());
		}
	}

	public String toString() {
		return new StringBuilder(super.toString())
			.append(" [")
			.append((this.choked ? "C" : "c"))
			.append((this.interested ? "I" : "i"))
			.append("|")
			.append((this.choking ? "C" : "c"))
			.append((this.interesting ? "I" : "i"))
			.append("]")
			.toString();
	}
}
package com.call;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.CloseReason;
import javax.websocket.Session;

import com.dao.CallDAO;
import com.db.DBConnect;
import com.entity.Call;
import com.entity.CallRole;
import com.entity.CallStatus;
import com.entity.CallType;

/**
 * Coeur du serveur de signalisation : connexions en ligne, appels en cours, relais des messages WebRTC.
 *
 * Regles appliquees :
 * - l'identite d'une connexion vient d'un ticket emis a une session HTTP authentifiee (jamais du client) ;
 * - toute operation sur un appel exige d'en etre un participant (sinon UNKNOWN_CALL, sans reveler l'existence) ;
 * - un seul appel actif (sonnerie ou conversation) par identite ;
 * - permissions verifiees par CallPolicy, etats valides et appliques par CallDAO ;
 * - messages valides (taille, type, champs), debit limite (messages et appels), SDP/ICE jamais journalises.
 *
 * Concurrence : tout l'etat est protege par un verrou unique ; les envois reseau se font APRES la liberation
 * du verrou (file "Out"), pour qu'un client lent ne bloque pas les autres.
 */
public final class CallHub {

	public interface ConnectionSource {
		Connection get() throws SQLException;
	}

	static final int MAX_TEXT = 32768;
	static final int MAX_SDP = 20000;
	static final int MAX_CANDIDATE = 2000;

	private static final int MAX_CONNS_PER_IDENTITY = 5;
	private static final int MAX_TOTAL_CONNS = 2000;
	private static final long IDLE_TIMEOUT_MS = 120000L;
	private static final int MSG_WINDOW_MS = 10000;
	private static final String KEY = "call.conn";

	private static final Logger LOG = Logger.getLogger(CallHub.class.getName());

	// ------------------------------------------------------------------ singleton

	private static volatile CallHub instance;

	public static CallHub instance() {
		CallHub h = instance;
		if (h == null) {
			synchronized (CallHub.class) {
				if (instance == null) {
					instance = new CallHub(new ConnectionSource() {
						public Connection get() throws SQLException {
							Connection c = DBConnect.getConn();
							if (c == null) {
								throw new SQLException("Base de donnees indisponible");
							}
							return c;
						}
					}, 1000L * intSetting("call.ring.timeout.sec", 45), 1000L * intSetting("call.reconnect.grace.sec", 20),
							intSetting("call.max.per.minute", 6), intSetting("call.max.msgs.per.10s", 150));
				}
				h = instance;
			}
		}
		return h;
	}

	/** Pour les tests : remplace l'instance globale. */
	static void setInstanceForTests(CallHub hub) {
		instance = hub;
	}

	private static int intSetting(String key, int def) {
		String v = System.getProperty(key);
		if (v == null || v.length() == 0) {
			v = System.getenv(key.toUpperCase().replace('.', '_'));
		}
		try {
			return (v == null || v.length() == 0) ? def : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	// ------------------------------------------------------------------ types internes

	private static final class Out {
		final Conn conn;
		final String json;

		Out(Conn conn, String json) {
			this.conn = conn;
			this.json = json;
		}
	}

	static final class Conn {
		final Session session;
		final Identity identity;
		final Object sendLock = new Object();
		long boundCallId; // protege par le verrou du hub
		long windowStart;
		int windowCount;
		int violations;
		int badMessages;

		Conn(Session session, Identity identity) {
			this.session = session;
			this.identity = identity;
		}

		void send(String json) {
			synchronized (sendLock) {
				if (!session.isOpen()) {
					return;
				}
				try {
					session.getBasicRemote().sendText(json);
				} catch (IOException e) {
					close(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "send failed");
				} catch (IllegalStateException e) {
					close(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "send failed");
				}
			}
		}

		void close(CloseReason.CloseCode code, String reason) {
			try {
				if (session.isOpen()) {
					session.close(new CloseReason(code, reason));
				}
			} catch (IOException e) {
				// deja ferme
			} catch (RuntimeException e) {
				// deja ferme
			}
		}
	}

	private static final class ActiveCall {
		final long id;
		final Identity caller;
		final Identity callee;
		final CallType media;
		CallStatus status = CallStatus.RINGING;
		Conn callerConn;
		Conn calleeConn;
		ScheduledFuture<?> ringTimer;
		ScheduledFuture<?> callerGrace;
		ScheduledFuture<?> calleeGrace;

		ActiveCall(long id, Identity caller, Identity callee, CallType media) {
			this.id = id;
			this.caller = caller;
			this.callee = callee;
			this.media = media;
		}
	}

	// ------------------------------------------------------------------ etat

	private final Object lock = new Object();
	private final Map<String, Set<Conn>> online = new HashMap<String, Set<Conn>>();
	private final Map<Long, ActiveCall> calls = new HashMap<Long, ActiveCall>();
	private final Map<String, Long> activeByIdentity = new HashMap<String, Long>();
	private final Map<String, Deque<Long>> callAttempts = new HashMap<String, Deque<Long>>();
	private int totalConns;

	private final ConnectionSource source;
	private final long ringMs;
	private final long graceMs;
	private final int maxCallsPerMinute;
	private final int maxMsgsPerWindow;
	private final ScheduledExecutorService scheduler;

	CallHub(ConnectionSource source, long ringMs, long graceMs, int maxCallsPerMinute, int maxMsgsPerWindow) {
		this.source = source;
		this.ringMs = ringMs;
		this.graceMs = graceMs;
		this.maxCallsPerMinute = maxCallsPerMinute;
		this.maxMsgsPerWindow = maxMsgsPerWindow;
		this.scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "call-hub-timer");
				t.setDaemon(true);
				return t;
			}
		});
	}

	// ------------------------------------------------------------------ evenements WebSocket

	public void open(Session session, String ticket) {
		Identity id = TicketStore.instance().redeem(ticket);
		if (id == null) {
			closeSession(session, CloseReason.CloseCodes.VIOLATED_POLICY, "Unauthorized");
			return;
		}
		session.setMaxIdleTimeout(IDLE_TIMEOUT_MS);
		session.setMaxTextMessageBufferSize(MAX_TEXT);

		Conn c = new Conn(session, id);
		List<Out> out = new ArrayList<Out>();
		boolean accepted = true;

		synchronized (lock) {
			Set<Conn> mine = online.get(id.key());
			if ((mine != null && mine.size() >= MAX_CONNS_PER_IDENTITY) || totalConns >= MAX_TOTAL_CONNS) {
				accepted = false;
			} else {
				if (mine == null) {
					mine = new LinkedHashSet<Conn>();
					online.put(id.key(), mine);
				}
				mine.add(c);
				totalConns++;
				session.getUserProperties().put(KEY, c);

				long callId = 0;
				String state = "NONE";
				Long active = activeByIdentity.get(id.key());
				ActiveCall ac = (active == null) ? null : calls.get(active);
				if (ac != null) {
					callId = ac.id;
					if (ac.status == CallStatus.ACCEPTED) {
						state = "ACTIVE";
					} else {
						state = ac.caller.equals(id) ? "OUTGOING" : "INCOMING";
					}
				}
				out.add(new Out(c, Json.obj("type", "ready", "role", id.getRole().name(), "id", id.getId(), "name",
						id.getName(), "callId", callId, "callState", state)));
				if (ac != null && ac.status == CallStatus.RINGING && ac.callee.equals(id)) {
					out.add(new Out(c, incomingJson(ac)));
				}
			}
		}
		if (!accepted) {
			closeSession(session, CloseReason.CloseCodes.VIOLATED_POLICY, "Too many connections");
			return;
		}
		flush(out);
	}

	public void message(Session session, String text) {
		Conn c = connOf(session);
		if (c == null) {
			return;
		}
		List<Out> out = new ArrayList<Out>();
		CloseReason.CloseCode closeCode = null;
		String closeText = null;

		synchronized (lock) {
			if (text == null || text.length() > MAX_TEXT) {
				closeCode = CloseReason.CloseCodes.TOO_BIG;
				closeText = "Message too large";
			} else if (!allowMessage(c)) {
				c.violations++;
				error(c, "RATE_LIMITED", 0, out);
				if (c.violations >= 3) {
					closeCode = CloseReason.CloseCodes.VIOLATED_POLICY;
					closeText = "Rate limit";
				}
			} else {
				Map<String, Object> m = null;
				try {
					m = Json.parseFlat(text);
				} catch (IllegalArgumentException e) {
					c.badMessages++;
					error(c, "BAD_MESSAGE", 0, out);
				}
				if (m != null) {
					dispatch(c, m, out);
				}
				if (c.badMessages >= 10) {
					closeCode = CloseReason.CloseCodes.VIOLATED_POLICY;
					closeText = "Too many invalid messages";
				}
			}
		}
		flush(out);
		if (closeCode != null) {
			c.close(closeCode, closeText);
		}
	}

	public void closed(Session session) {
		Conn c = connOf(session);
		if (c == null) {
			return;
		}
		List<Out> out = new ArrayList<Out>();
		synchronized (lock) {
			Set<Conn> mine = online.get(c.identity.key());
			if (mine != null && mine.remove(c)) {
				totalConns--;
				if (mine.isEmpty()) {
					online.remove(c.identity.key());
				}
			}
			detach(c, out);
		}
		flush(out);
	}

	public void failed(Session session) {
		Conn c = connOf(session);
		if (c != null) {
			c.close(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "error");
		}
	}

	private Conn connOf(Session session) {
		Object o = session.getUserProperties().get(KEY);
		return (o instanceof Conn) ? (Conn) o : null;
	}

	private void closeSession(Session s, CloseReason.CloseCode code, String reason) {
		try {
			if (s.isOpen()) {
				s.close(new CloseReason(code, reason));
			}
		} catch (IOException e) {
			// deja ferme
		} catch (RuntimeException e) {
			// deja ferme
		}
	}

	// ------------------------------------------------------------------ aiguillage (verrou detenu)

	private void dispatch(Conn c, Map<String, Object> m, List<Out> out) {
		Object t = m.get("type");
		if (!(t instanceof String)) {
			c.badMessages++;
			error(c, "BAD_MESSAGE", 0, out);
			return;
		}
		String type = (String) t;

		if (type.equals("ping")) {
			out.add(new Out(c, Json.obj("type", "pong")));
		} else if (type.equals("call")) {
			handleCall(c, m, out);
		} else if (type.equals("accept") || type.equals("reject") || type.equals("cancel") || type.equals("hangup")
				|| type.equals("resume") || type.equals("offer") || type.equals("answer") || type.equals("ice")) {
			Long callId = longField(m, "callId");
			if (callId == null || callId.longValue() <= 0) {
				c.badMessages++;
				error(c, "BAD_MESSAGE", 0, out);
				return;
			}
			ActiveCall ac = calls.get(callId);
			if (ac == null || !(ac.caller.equals(c.identity) || ac.callee.equals(c.identity))) {
				error(c, "UNKNOWN_CALL", callId.longValue(), out); // meme reponse : n'existe pas / pas participant
				return;
			}
			if (type.equals("accept")) {
				handleAccept(c, ac, out);
			} else if (type.equals("reject")) {
				handleReject(c, ac, out);
			} else if (type.equals("cancel")) {
				handleCancel(c, ac, out);
			} else if (type.equals("hangup")) {
				handleHangup(c, ac, out);
			} else if (type.equals("resume")) {
				handleResume(c, ac, out);
			} else {
				handleRelay(c, ac, type, m, out);
			}
		} else {
			c.badMessages++;
			error(c, "BAD_MESSAGE", 0, out);
		}
	}

	// ------------------------------------------------------------------ appels

	private void handleCall(Conn c, Map<String, Object> m, List<Out> out) {
		CallRole role = roleOf(m.get("toRole"));
		Long toId = longField(m, "toId");
		CallType media = mediaOf(m.get("media"));
		if (role == null || toId == null || toId.longValue() <= 0 || toId.longValue() > Integer.MAX_VALUE
				|| media == null) {
			c.badMessages++;
			error(c, "BAD_MESSAGE", 0, out);
			return;
		}
		if (!allowCallAttempt(c.identity)) {
			error(c, "RATE_LIMITED", 0, out);
			return;
		}
		if (activeByIdentity.containsKey(c.identity.key())) {
			error(c, "BUSY_SELF", 0, out);
			return;
		}

		Identity callee = new Identity(role, toId.intValue(), null);
		Connection cn = null;
		try {
			cn = source.get();
			CallDAO dao = new CallDAO(cn);

			CallPolicy.Decision d = new CallPolicy(dao).check(c.identity.getRole(), c.identity.getId(), role,
					toId.intValue());
			if (!d.isAllowed()) {
				LOG.info("Appel refuse (" + d.getReason() + ") " + c.identity.key() + " -> " + callee.key());
				error(c, "NOT_ALLOWED", 0, out); // reponse identique quelle que soit la raison
				return;
			}

			Set<Conn> calleeConns = online.get(callee.key());
			boolean isOnline = calleeConns != null && !calleeConns.isEmpty();
			boolean busy = activeByIdentity.containsKey(callee.key());
			boolean ringing = isOnline && !busy;

			Call row = new Call();
			row.setCallerRole(c.identity.getRole());
			row.setCallerId(c.identity.getId());
			row.setCalleeRole(role);
			row.setCalleeId(toId.intValue());
			row.setType(media);
			row.setStatus(ringing ? CallStatus.RINGING : CallStatus.MISSED);
			row.setAppointmentId(d.getAppointmentId());
			long id = dao.create(row, now());
			if (id <= 0) {
				error(c, "SERVER_ERROR", 0, out);
				return;
			}

			if (!ringing) {
				out.add(new Out(c, Json.obj("type", "unavailable", "callId", id, "reason", busy ? "BUSY" : "OFFLINE")));
				return;
			}

			final ActiveCall ac = new ActiveCall(id, c.identity, new Identity(role, toId.intValue(), null), media);
			ac.callerConn = c;
			c.boundCallId = id;
			calls.put(Long.valueOf(id), ac);
			activeByIdentity.put(c.identity.key(), Long.valueOf(id));
			activeByIdentity.put(callee.key(), Long.valueOf(id));
			ac.ringTimer = scheduler.schedule(new Runnable() {
				public void run() {
					ringTimeout(ac.id);
				}
			}, ringMs, TimeUnit.MILLISECONDS);

			out.add(new Out(c, Json.obj("type", "ringing", "callId", id, "media", media.name())));
			String incoming = incomingJson(ac);
			for (Conn target : calleeConns) {
				out.add(new Out(target, incoming));
			}
			LOG.info("Appel " + id + " " + media + " " + c.identity.key() + " -> " + callee.key());
		} catch (SQLException e) {
			LOG.log(Level.WARNING, "Appel : base de donnees indisponible", e);
			error(c, "SERVER_ERROR", 0, out);
		} finally {
			closeQuietly(cn);
		}
	}

	private String incomingJson(ActiveCall ac) {
		return Json.obj("type", "incoming", "callId", ac.id, "fromRole", ac.caller.getRole().name(), "fromId",
				ac.caller.getId(), "fromName", ac.caller.getName(), "media", ac.media.name());
	}

	private void handleAccept(Conn c, ActiveCall ac, List<Out> out) {
		if (ac.status != CallStatus.RINGING || !ac.callee.equals(c.identity)) {
			error(c, "BAD_STATE", ac.id, out);
			return;
		}
		if (!dbTransition(ac.id, CallStatus.ACCEPTED)) {
			error(c, "BAD_STATE", ac.id, out);
			return;
		}
		cancel(ac.ringTimer);
		ac.status = CallStatus.ACCEPTED;
		ac.calleeConn = c;
		c.boundCallId = ac.id;

		String accepted = Json.obj("type", "accepted", "callId", ac.id);
		if (ac.callerConn != null) {
			out.add(new Out(ac.callerConn, accepted));
		}
		out.add(new Out(c, accepted));
		Set<Conn> others = online.get(ac.callee.key());
		if (others != null) {
			for (Conn o : others) {
				if (o != c) {
					out.add(new Out(o, Json.obj("type", "taken", "callId", ac.id)));
				}
			}
		}
	}

	private void handleReject(Conn c, ActiveCall ac, List<Out> out) {
		if (ac.status != CallStatus.RINGING || !ac.callee.equals(c.identity)) {
			error(c, "BAD_STATE", ac.id, out);
			return;
		}
		finish(ac, CallStatus.REJECTED, out);
	}

	private void handleCancel(Conn c, ActiveCall ac, List<Out> out) {
		if (ac.status != CallStatus.RINGING || ac.callerConn != c) {
			error(c, "BAD_STATE", ac.id, out);
			return;
		}
		finish(ac, CallStatus.CANCELLED, out);
	}

	private void handleHangup(Conn c, ActiveCall ac, List<Out> out) {
		if (ac.status == CallStatus.ACCEPTED && (ac.callerConn == c || ac.calleeConn == c)) {
			finish(ac, CallStatus.ENDED, out);
		} else if (ac.status == CallStatus.RINGING && ac.callerConn == c) {
			finish(ac, CallStatus.CANCELLED, out);
		} else {
			error(c, "BAD_STATE", ac.id, out);
		}
	}

	private void handleResume(Conn c, ActiveCall ac, List<Out> out) {
		if (ac.status != CallStatus.ACCEPTED) {
			error(c, "BAD_STATE", ac.id, out);
			return;
		}
		boolean isCaller = ac.caller.equals(c.identity);
		Conn current = isCaller ? ac.callerConn : ac.calleeConn;
		if (current != null && current != c) {
			error(c, "BAD_STATE", ac.id, out); // cote deja lie a une connexion encore ouverte
			return;
		}
		if (isCaller) {
			cancel(ac.callerGrace);
			ac.callerConn = c;
		} else {
			cancel(ac.calleeGrace);
			ac.calleeConn = c;
		}
		c.boundCallId = ac.id;
		Conn peer = isCaller ? ac.calleeConn : ac.callerConn;
		out.add(new Out(c, Json.obj("type", "resumed", "callId", ac.id, "side", isCaller ? "caller" : "callee",
				"peerOnline", peer != null)));
		if (peer != null) {
			out.add(new Out(peer, Json.obj("type", "peer_back", "callId", ac.id)));
		}
	}

	private void handleRelay(Conn c, ActiveCall ac, String type, Map<String, Object> m, List<Out> out) {
		if (ac.status != CallStatus.ACCEPTED || (ac.callerConn != c && ac.calleeConn != c)) {
			error(c, "BAD_STATE", ac.id, out);
			return;
		}
		String field = type.equals("ice") ? "candidate" : "sdp";
		int max = type.equals("ice") ? MAX_CANDIDATE : MAX_SDP;
		Object v = m.get(field);
		if (!(v instanceof String) || ((String) v).length() == 0 || ((String) v).length() > max) {
			c.badMessages++;
			error(c, "BAD_MESSAGE", ac.id, out);
			return;
		}
		Conn peer = (ac.callerConn == c) ? ac.calleeConn : ac.callerConn;
		if (peer == null) {
			error(c, "PEER_UNAVAILABLE", ac.id, out);
			return;
		}
		// Message reconstruit : seuls type, callId et la charge utile sont transmis (aucun champ "from" client).
		out.add(new Out(peer, Json.obj("type", type, "callId", ac.id, field, v)));
	}

	// ------------------------------------------------------------------ fin d'appel, minuteries (verrou detenu)

	private void finish(ActiveCall ac, CallStatus to, List<Out> out) {
		boolean wasRinging = ac.status == CallStatus.RINGING;
		if (!dbTransition(ac.id, to)) {
			LOG.warning("Appel " + ac.id + " : transition " + to + " non enregistree en base");
		}
		cancel(ac.ringTimer);
		cancel(ac.callerGrace);
		cancel(ac.calleeGrace);
		calls.remove(Long.valueOf(ac.id));
		activeByIdentity.remove(ac.caller.key());
		activeByIdentity.remove(ac.callee.key());

		Set<Conn> targets = new LinkedHashSet<Conn>();
		if (ac.callerConn != null) {
			targets.add(ac.callerConn);
		}
		if (ac.calleeConn != null) {
			targets.add(ac.calleeConn);
		}
		if (wasRinging) {
			Set<Conn> calleeConns = online.get(ac.callee.key());
			if (calleeConns != null) {
				targets.addAll(calleeConns);
			}
		}
		String msg = Json.obj("type", "ended", "callId", ac.id, "status", to.name());
		for (Conn t : targets) {
			if (t.boundCallId == ac.id) {
				t.boundCallId = 0;
			}
			out.add(new Out(t, msg));
		}
		LOG.info("Appel " + ac.id + " termine : " + to);
	}

	/** Une connexion se ferme : sonnerie -> annulation ; conversation -> delai de reconnexion. */
	private void detach(Conn c, List<Out> out) {
		long cid = c.boundCallId;
		if (cid == 0) {
			return;
		}
		c.boundCallId = 0;
		final ActiveCall ac = calls.get(Long.valueOf(cid));
		if (ac == null) {
			return;
		}
		if (ac.status == CallStatus.RINGING) {
			if (ac.callerConn == c) {
				finish(ac, CallStatus.CANCELLED, out);
			}
			return;
		}
		final boolean isCaller = ac.callerConn == c;
		if (!isCaller && ac.calleeConn != c) {
			return;
		}
		Conn peer;
		if (isCaller) {
			ac.callerConn = null;
			peer = ac.calleeConn;
			ac.callerGrace = scheduler.schedule(new Runnable() {
				public void run() {
					graceExpired(ac.id, true);
				}
			}, graceMs, TimeUnit.MILLISECONDS);
		} else {
			ac.calleeConn = null;
			peer = ac.callerConn;
			ac.calleeGrace = scheduler.schedule(new Runnable() {
				public void run() {
					graceExpired(ac.id, false);
				}
			}, graceMs, TimeUnit.MILLISECONDS);
		}
		if (peer != null) {
			out.add(new Out(peer, Json.obj("type", "peer_offline", "callId", ac.id)));
		}
	}

	private void ringTimeout(long callId) {
		List<Out> out = new ArrayList<Out>();
		synchronized (lock) {
			ActiveCall ac = calls.get(Long.valueOf(callId));
			if (ac != null && ac.status == CallStatus.RINGING) {
				finish(ac, CallStatus.MISSED, out);
			}
		}
		flush(out);
	}

	private void graceExpired(long callId, boolean callerSide) {
		List<Out> out = new ArrayList<Out>();
		synchronized (lock) {
			ActiveCall ac = calls.get(Long.valueOf(callId));
			if (ac != null && ac.status == CallStatus.ACCEPTED && (callerSide ? ac.callerConn == null : ac.calleeConn == null)) {
				finish(ac, CallStatus.DROPPED, out);
			}
		}
		flush(out);
	}

	// ------------------------------------------------------------------ limites

	private boolean allowMessage(Conn c) {
		long now = System.currentTimeMillis();
		if (now - c.windowStart > MSG_WINDOW_MS) {
			c.windowStart = now;
			c.windowCount = 0;
		}
		c.windowCount++;
		return c.windowCount <= maxMsgsPerWindow;
	}

	private boolean allowCallAttempt(Identity id) {
		long now = System.currentTimeMillis();
		Deque<Long> q = callAttempts.get(id.key());
		if (q == null) {
			q = new ArrayDeque<Long>();
			callAttempts.put(id.key(), q);
		}
		while (!q.isEmpty() && now - q.peekFirst().longValue() > 60000L) {
			q.pollFirst();
		}
		if (q.size() >= maxCallsPerMinute) {
			return false;
		}
		q.addLast(Long.valueOf(now));
		if (callAttempts.size() > 5000) {
			for (java.util.Iterator<Map.Entry<String, Deque<Long>>> it = callAttempts.entrySet().iterator(); it.hasNext();) {
				Deque<Long> d = it.next().getValue();
				if (d.isEmpty() || now - d.peekLast().longValue() > 60000L) {
					it.remove();
				}
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ utilitaires

	private void error(Conn c, String code, long callId, List<Out> out) {
		out.add(new Out(c, callId > 0 ? Json.obj("type", "error", "code", code, "callId", callId)
				: Json.obj("type", "error", "code", code)));
	}

	private void flush(List<Out> out) {
		for (Out o : out) {
			o.conn.send(o.json);
		}
	}

	private static void cancel(ScheduledFuture<?> f) {
		if (f != null) {
			f.cancel(false);
		}
	}

	private static Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}

	private boolean dbTransition(long callId, CallStatus to) {
		Connection cn = null;
		try {
			cn = source.get();
			return new CallDAO(cn).transition(callId, to, now());
		} catch (SQLException e) {
			LOG.log(Level.WARNING, "Transition " + to + " impossible : base de donnees indisponible", e);
			return false;
		} finally {
			closeQuietly(cn);
		}
	}

	private static void closeQuietly(Connection c) {
		if (c != null) {
			try {
				c.close();
			} catch (SQLException e) {
				// rien a faire
			}
		}
	}

	private static Long longField(Map<String, Object> m, String key) {
		Object v = m.get(key);
		if (v instanceof Long) {
			return (Long) v;
		}
		return null; // refuse aussi les nombres a virgule et les textes
	}

	private static CallRole roleOf(Object v) {
		if ("USER".equals(v)) {
			return CallRole.USER;
		}
		if ("DOCTOR".equals(v)) {
			return CallRole.DOCTOR;
		}
		return null;
	}

	private static CallType mediaOf(Object v) {
		if ("AUDIO".equals(v)) {
			return CallType.AUDIO;
		}
		if ("VIDEO".equals(v)) {
			return CallType.VIDEO;
		}
		return null;
	}

	// ------------------------------------------------------------------ cycle de vie

	/** Au demarrage : cloture les appels restes ouverts (RINGING -> MISSED, ACCEPTED -> DROPPED). */
	public int closeOrphans() throws SQLException {
		Connection cn = source.get();
		try {
			return new CallDAO(cn).closeOrphans(now());
		} finally {
			closeQuietly(cn);
		}
	}

	/** A l'arret de l'application : termine les appels en cours et ferme les connexions. */
	public void shutdown() {
		List<Out> out = new ArrayList<Out>();
		List<Conn> all = new ArrayList<Conn>();
		synchronized (lock) {
			for (ActiveCall ac : new ArrayList<ActiveCall>(calls.values())) {
				finish(ac, ac.status == CallStatus.RINGING ? CallStatus.MISSED : CallStatus.DROPPED, out);
			}
			for (Set<Conn> s : online.values()) {
				all.addAll(s);
			}
			online.clear();
			totalConns = 0;
		}
		flush(out);
		for (Conn c : all) {
			c.close(CloseReason.CloseCodes.GOING_AWAY, "Server shutdown");
		}
		scheduler.shutdownNow();
	}

	// Pour les tests
	int activeCallCount() {
		synchronized (lock) {
			return calls.size();
		}
	}

	int onlineConnectionCount() {
		synchronized (lock) {
			return totalConns;
		}
	}
}

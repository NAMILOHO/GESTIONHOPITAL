package com.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.entity.Call;
import com.entity.CallRole;
import com.entity.CallStatus;
import com.entity.CallType;

/**
 * Acces a la table calls. Les changements d'etat passent TOUS par transition() :
 * la transition est validee (CallStatus.canTransitionTo) puis appliquee par un UPDATE
 * conditionnel "... AND status = <etat attendu>", donc atomique meme si deux evenements
 * arrivent en meme temps (par exemple "accepter" et "annuler").
 */
public class CallDAO {

	private static final String COLUMNS = "id, caller_role, caller_id, callee_role, callee_id, call_type, status, "
			+ "appointment_id, created_at, answered_at, ended_at, duration_sec";

	private static final int MAX_PAGE = 100;

	private Connection conn;

	public CallDAO(Connection conn) {
		super();
		this.conn = conn;
	}

	/**
	 * Cree l'appel. Statut initial : RINGING, ou MISSED si l'appele est indisponible
	 * (hors ligne ou occupe) : l'appel est alors deja clos. Renvoie l'id, ou -1 en cas d'echec.
	 */
	public long create(Call c, Timestamp now) {
		if (c.getStatus() != CallStatus.RINGING && c.getStatus() != CallStatus.MISSED) {
			throw new IllegalArgumentException("Statut initial invalide : " + c.getStatus());
		}
		PreparedStatement ps = null;
		ResultSet keys = null;
		try {
			boolean closed = c.getStatus() == CallStatus.MISSED;
			String sql = "insert into calls(caller_role, caller_id, callee_role, callee_id, call_type, status, "
					+ "appointment_id, created_at, ended_at, duration_sec) values(?,?,?,?,?,?,?,?,?,?)";
			ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, c.getCallerRole().name());
			ps.setInt(2, c.getCallerId());
			ps.setString(3, c.getCalleeRole().name());
			ps.setInt(4, c.getCalleeId());
			ps.setString(5, c.getType().name());
			ps.setString(6, c.getStatus().name());
			if (c.getAppointmentId() == null) {
				ps.setNull(7, java.sql.Types.INTEGER);
			} else {
				ps.setInt(7, c.getAppointmentId().intValue());
			}
			ps.setTimestamp(8, now);
			if (closed) {
				ps.setTimestamp(9, now);
				ps.setInt(10, 0);
			} else {
				ps.setNull(9, java.sql.Types.TIMESTAMP);
				ps.setNull(10, java.sql.Types.INTEGER);
			}
			if (ps.executeUpdate() != 1) {
				return -1;
			}
			keys = ps.getGeneratedKeys();
			if (keys.next()) {
				return keys.getLong(1);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			close(keys);
			close(ps);
		}
		return -1;
	}

	/**
	 * Applique une transition d'etat. Renvoie true si elle a ete appliquee, false si elle est
	 * interdite ou si l'appel a change d'etat entre-temps.
	 */
	public boolean transition(long id, CallStatus to, Timestamp now) {
		Call current = find(id);
		if (current == null || !current.getStatus().canTransitionTo(to)) {
			return false;
		}

		PreparedStatement ps = null;
		try {
			String sql;
			if (to == CallStatus.ACCEPTED) {
				sql = "update calls set status=?, answered_at=? where id=? and status=?";
				ps = conn.prepareStatement(sql);
				ps.setString(1, to.name());
				ps.setTimestamp(2, now);
				ps.setLong(3, id);
				ps.setString(4, current.getStatus().name());
			} else {
				sql = "update calls set status=?, ended_at=?, duration_sec=? where id=? and status=?";
				ps = conn.prepareStatement(sql);
				ps.setString(1, to.name());
				ps.setTimestamp(2, now);
				ps.setInt(3, durationSeconds(current, to, now));
				ps.setLong(4, id);
				ps.setString(5, current.getStatus().name());
			}
			return ps.executeUpdate() == 1;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		} finally {
			close(ps);
		}
	}

	/** Duree de conversation : uniquement pour un appel qui avait ete accepte. */
	private static int durationSeconds(Call current, CallStatus to, Timestamp now) {
		if (current.getStatus() == CallStatus.ACCEPTED && current.getAnsweredAt() != null) {
			long ms = now.getTime() - current.getAnsweredAt().getTime();
			return (int) Math.max(0L, ms / 1000L);
		}
		return 0;
	}

	public Call find(long id) {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = conn.prepareStatement("select " + COLUMNS + " from calls where id=?");
			ps.setLong(1, id);
			rs = ps.executeQuery();
			if (rs.next()) {
				return map(rs);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			close(rs);
			close(ps);
		}
		return null;
	}

	/** Historique d'un participant (appels emis et recus), du plus recent au plus ancien. */
	public List<Call> history(CallRole role, int userId, int limit, int offset) {
		List<Call> list = new ArrayList<Call>();
		int lim = Math.max(1, Math.min(limit, MAX_PAGE));
		int off = Math.max(0, offset);

		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "select " + COLUMNS + " from calls where (caller_role=? and caller_id=?) "
					+ "or (callee_role=? and callee_id=?) order by created_at desc, id desc limit ? offset ?";
			ps = conn.prepareStatement(sql);
			ps.setString(1, role.name());
			ps.setInt(2, userId);
			ps.setString(3, role.name());
			ps.setInt(4, userId);
			ps.setInt(5, lim);
			ps.setInt(6, off);
			rs = ps.executeQuery();
			while (rs.next()) {
				list.add(map(rs));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			close(rs);
			close(ps);
		}
		return list;
	}

	/** Id d'un rendez-vous commun entre ce patient et ce medecin, ou null s'il n'y en a aucun. */
	public Integer findSharedAppointment(int patientId, int doctorId) {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = conn.prepareStatement(
					"select id from appointment where user_id=? and doctor_id=? order by id desc limit 1");
			ps.setInt(1, patientId);
			ps.setInt(2, doctorId);
			rs = ps.executeQuery();
			if (rs.next()) {
				return Integer.valueOf(rs.getInt(1));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			close(rs);
			close(ps);
		}
		return null;
	}

	/**
	 * A appeler au demarrage du serveur : un redemarrage coupe toutes les connexions, donc les appels
	 * restes RINGING deviennent MISSED et ceux restes ACCEPTED deviennent DROPPED.
	 */
	public int closeOrphans(Timestamp now) {
		List<Long> ids = new ArrayList<Long>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = conn.prepareStatement("select id from calls where status in ('RINGING','ACCEPTED')");
			rs = ps.executeQuery();
			while (rs.next()) {
				ids.add(Long.valueOf(rs.getLong(1)));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			close(rs);
			close(ps);
		}

		int n = 0;
		for (Long id : ids) {
			Call c = find(id.longValue());
			if (c == null) {
				continue;
			}
			CallStatus to = (c.getStatus() == CallStatus.RINGING) ? CallStatus.MISSED : CallStatus.DROPPED;
			if (transition(id.longValue(), to, now)) {
				n++;
			}
		}
		return n;
	}

	private static Call map(ResultSet rs) throws SQLException {
		Call c = new Call();
		c.setId(rs.getLong(1));
		c.setCallerRole(CallRole.valueOf(rs.getString(2)));
		c.setCallerId(rs.getInt(3));
		c.setCalleeRole(CallRole.valueOf(rs.getString(4)));
		c.setCalleeId(rs.getInt(5));
		c.setType(CallType.valueOf(rs.getString(6)));
		c.setStatus(CallStatus.valueOf(rs.getString(7)));
		int appt = rs.getInt(8);
		c.setAppointmentId(rs.wasNull() ? null : Integer.valueOf(appt));
		c.setCreatedAt(rs.getTimestamp(9));
		c.setAnsweredAt(rs.getTimestamp(10));
		c.setEndedAt(rs.getTimestamp(11));
		int dur = rs.getInt(12);
		c.setDurationSec(rs.wasNull() ? null : Integer.valueOf(dur));
		return c;
	}

	private static void close(ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException e) {
				// rien a faire
			}
		}
	}

	private static void close(Statement st) {
		if (st != null) {
			try {
				st.close();
			} catch (SQLException e) {
				// rien a faire
			}
		}
	}

}

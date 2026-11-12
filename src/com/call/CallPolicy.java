package com.call;

import com.dao.CallDAO;
import com.entity.CallRole;

/**
 * Regle de permission des appels. Toute la politique est ICI : pour la changer (par exemple exiger un
 * rendez-vous traite par le medecin), il suffit de modifier check().
 *
 * Regle actuelle : un patient et un medecin peuvent s'appeler, dans les deux sens, s'ils ont au moins
 * un rendez-vous commun dans la table appointment. Pas d'appel patient-patient, medecin-medecin ni
 * avec l'administrateur (CallRole n'a pas de valeur ADMIN), et pas d'appel a soi-meme.
 */
public class CallPolicy {

	public enum Reason {
		ALLOWED, INVALID_PARTICIPANT, SAME_ROLE, NO_APPOINTMENT
	}

	public static final class Decision {
		private final Reason reason;
		private final Integer appointmentId;

		Decision(Reason reason, Integer appointmentId) {
			this.reason = reason;
			this.appointmentId = appointmentId;
		}

		public boolean isAllowed() {
			return reason == Reason.ALLOWED;
		}

		public Reason getReason() {
			return reason;
		}

		/** Rendez-vous commun qui justifie l'appel (non null si autorise). */
		public Integer getAppointmentId() {
			return appointmentId;
		}
	}

	private final CallDAO dao;

	public CallPolicy(CallDAO dao) {
		this.dao = dao;
	}

	public Decision check(CallRole callerRole, int callerId, CallRole calleeRole, int calleeId) {
		if (callerRole == null || calleeRole == null || callerId <= 0 || calleeId <= 0) {
			return new Decision(Reason.INVALID_PARTICIPANT, null);
		}
		if (callerRole == calleeRole) {
			return new Decision(Reason.SAME_ROLE, null);
		}

		int patientId = (callerRole == CallRole.USER) ? callerId : calleeId;
		int doctorId = (callerRole == CallRole.DOCTOR) ? callerId : calleeId;

		Integer appointmentId = dao.findSharedAppointment(patientId, doctorId);
		if (appointmentId == null) {
			return new Decision(Reason.NO_APPOINTMENT, null);
		}
		return new Decision(Reason.ALLOWED, appointmentId);
	}
}

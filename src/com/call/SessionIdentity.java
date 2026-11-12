package com.call;

import javax.servlet.http.HttpSession;

import com.entity.CallRole;
import com.entity.Doctor;
import com.entity.User;

/**
 * Deduit l'identite d'appel (patient ou medecin) de la session HTTP, avec le code HTTP a renvoyer en cas
 * de probleme : 401 non connecte, 403 administrateur (pas d'appels), 409 session ambigue (deux roles).
 */
final class SessionIdentity {

	static final class Result {
		final Identity identity;
		final int status;

		Result(Identity identity, int status) {
			this.identity = identity;
			this.status = status;
		}

		boolean ok() {
			return identity != null;
		}
	}

	private SessionIdentity() {
	}

	static Result resolve(HttpSession session) {
		Object user = (session == null) ? null : session.getAttribute("userObj");
		Object doctor = (session == null) ? null : session.getAttribute("doctObj");
		Object admin = (session == null) ? null : session.getAttribute("adminObj");

		if (user == null && doctor == null) {
			return new Result(null, admin != null ? 403 : 401);
		}
		if (user != null && doctor != null) {
			return new Result(null, 409);
		}
		if (user instanceof User) {
			User u = (User) user;
			return new Result(new Identity(CallRole.USER, u.getId(), u.getFullName()), 200);
		}
		if (doctor instanceof Doctor) {
			Doctor d = (Doctor) doctor;
			return new Result(new Identity(CallRole.DOCTOR, d.getId(), d.getFullName()), 200);
		}
		return new Result(null, 403);
	}
}

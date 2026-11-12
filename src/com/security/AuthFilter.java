package com.security;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.entity.Doctor;
import com.entity.User;

/**
 * Controle d'acces central.
 *
 * 1. Les zones /admin/*, /doctor/* et les servlets sensibles exigent une session du bon role.
 * 2. Pour les servlets qui recoivent un identifiant dans la requete (uid, userid, did, id),
 *    cet identifiant doit etre celui de l'utilisateur connecte : on ne fait plus confiance au client.
 *
 * Mappe sur /* : la decision se prend ici, apres mise en minuscules du chemin, car le
 * mapping d'URL de Tomcat est sensible a la casse alors que le systeme de fichiers Windows
 * ne l'est pas (/Admin/index.jsp pourrait sinon contourner un filtre sur /admin/*).
 */
@WebFilter(urlPatterns = "/*")
public class AuthFilter implements Filter {

	private static final String ADMIN = "adminObj";
	private static final String DOCTOR = "doctObj";
	private static final String USER = "userObj";

	/** servlet sensible (minuscules) -> role requis */
	private static final Map<String, String> SERVLET_ROLE = new HashMap<String, String>();
	/** servlet sensible (minuscules) -> parametre devant contenir l'id de l'utilisateur connecte */
	private static final Map<String, String> BOUND_PARAM = new HashMap<String, String>();

	static {
		SERVLET_ROLE.put("/adddoctor", ADMIN);
		SERVLET_ROLE.put("/updatedoctor", ADMIN);
		SERVLET_ROLE.put("/deletedoctor", ADMIN);
		SERVLET_ROLE.put("/addspecialist", ADMIN);

		SERVLET_ROLE.put("/updatestatus", DOCTOR);
		SERVLET_ROLE.put("/doctorupdateprofile", DOCTOR);
		SERVLET_ROLE.put("/doctchangepassword", DOCTOR);
		BOUND_PARAM.put("/updatestatus", "did");
		BOUND_PARAM.put("/doctorupdateprofile", "id");
		BOUND_PARAM.put("/doctchangepassword", "uid");

		SERVLET_ROLE.put("/appappointment", USER);
		SERVLET_ROLE.put("/userchangepassword", USER);

		// Pages patient qui supposent une session patient : sans elle, view_appointment.jsp plante (NullPointerException).
		SERVLET_ROLE.put("/view_appointment.jsp", USER);
		SERVLET_ROLE.put("/change_password.jsp", USER);
		BOUND_PARAM.put("/appappointment", "userid");
		BOUND_PARAM.put("/userchangepassword", "uid");
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		HttpServletRequest req = (HttpServletRequest) request;
		HttpServletResponse resp = (HttpServletResponse) response;

		String path = req.getServletPath().toLowerCase(Locale.ROOT);
		String role = requiredRole(path);

		if (role == null) {
			chain.doFilter(request, response);
			return;
		}

		HttpSession session = req.getSession(false);
		Object principal = (session == null) ? null : session.getAttribute(role);

		if (principal == null) {
			resp.sendRedirect(req.getContextPath() + "/" + loginPage(role));
			return;
		}

		String param = BOUND_PARAM.get(path);
		if (param != null) {
			Integer sessionId = idOf(principal);
			Integer requestedId = parseInt(req.getParameter(param));
			if (sessionId == null || requestedId == null || !sessionId.equals(requestedId)) {
				resp.sendError(HttpServletResponse.SC_FORBIDDEN);
				return;
			}
		}

		// Les pages protegees ne doivent pas rester consultables via le bouton "Retour" apres deconnexion.
		resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
		resp.setHeader("Pragma", "no-cache");
		resp.setDateHeader("Expires", 0);

		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
	}

	private static String requiredRole(String path) {
		if (path.equals("/admin") || path.startsWith("/admin/")) {
			return ADMIN;
		}
		if (path.equals("/doctor") || path.startsWith("/doctor/")) {
			return DOCTOR;
		}
		return SERVLET_ROLE.get(path);
	}

	private static String loginPage(String role) {
		if (ADMIN.equals(role)) {
			return "admin_login.jsp";
		}
		if (DOCTOR.equals(role)) {
			return "doctor_login.jsp";
		}
		return "user_login.jsp";
	}

	private static Integer idOf(Object principal) {
		if (principal instanceof Doctor) {
			return Integer.valueOf(((Doctor) principal).getId());
		}
		if (principal instanceof User) {
			return Integer.valueOf(((User) principal).getId());
		}
		return null;
	}

	private static Integer parseInt(String s) {
		if (s == null) {
			return null;
		}
		try {
			return Integer.valueOf(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}

package com.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.entity.User;
import com.security.PasswordUtil;

public class UserDAO {

	private Connection conn;

	public UserDAO(Connection conn) {
		super();
		this.conn = conn;
	}

	public boolean register(User u) {
		boolean f = false;

		try {
			String sql = "insert into user_dtls(full_name,email,password) values(?,?,?) ";

			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setString(1, u.getFullName());
			ps.setString(2, u.getEmail());
			ps.setString(3, PasswordUtil.hash(u.getPassword()));

			int i = ps.executeUpdate();

			if (i == 1) {
				f = true;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return f;
	}

	public User login(String em, String psw) {
		User u = null;

		try {
			// La verification du mot de passe se fait en Java (hash PBKDF2), plus dans le SQL.
			String sql = "select * from user_dtls where email=?";
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setString(1, em);

			ResultSet rs = ps.executeQuery();

			while (rs.next()) {
				String stored = rs.getString(4);
				boolean legacy = false;
				boolean ok = false;

				if (PasswordUtil.isHashed(stored)) {
					ok = PasswordUtil.verify(psw, stored);
				} else if (PasswordUtil.matchesLegacy(psw, stored)) {
					ok = true;
					legacy = true;
				}

				if (ok) {
					u = new User();
					u.setId(rs.getInt(1));
					u.setFullName(rs.getString(2));
					u.setEmail(rs.getString(3));
					// le mot de passe (ou son hash) ne doit pas rester dans l'objet place en session
					u.setPassword("");

					if (legacy) {
						upgradeLegacyPassword(em, psw);
					}
					break;
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return u;
	}

	/** Remplace un ancien mot de passe en clair par son hash (migration a la connexion). */
	private void upgradeLegacyPassword(String email, String legacyPlain) {
		try {
			String sql = "update user_dtls set password=? where email=? and password=?";
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setString(1, PasswordUtil.hash(legacyPlain));
			ps.setString(2, email);
			ps.setString(3, legacyPlain);
			ps.executeUpdate();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public boolean checkOldPassword(int userid, String oldPassword) {
		boolean f = false;

		try {
			String sql = "select * from user_dtls where id=?";
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, userid);

			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				String stored = rs.getString(4);
				if (PasswordUtil.isHashed(stored)) {
					f = PasswordUtil.verify(oldPassword, stored);
				} else {
					f = PasswordUtil.matchesLegacy(oldPassword, stored);
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return f;
	}

	public boolean changePassword(int userid, String newPassword) {
		boolean f = false;

		try {
			String sql = "update user_dtls set password=? where id=?";
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setString(1, PasswordUtil.hash(newPassword));
			ps.setInt(2, userid);

			int i = ps.executeUpdate();
			if (i == 1) {
				f = true;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return f;
	}

}

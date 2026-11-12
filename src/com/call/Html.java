package com.call;

/** Echappement HTML pour les valeurs inserees dans les pages (noms saisis par les utilisateurs). */
public final class Html {

	private Html() {
	}

	/** Echappe & < > " ' ` : valable dans le texte comme dans les attributs HTML entre guillemets. */
	public static String esc(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '&':
				sb.append("&amp;");
				break;
			case '<':
				sb.append("&lt;");
				break;
			case '>':
				sb.append("&gt;");
				break;
			case '"':
				sb.append("&quot;");
				break;
			case '\'':
				sb.append("&#39;");
				break;
			case '`':
				sb.append("&#96;");
				break;
			default:
				sb.append(c);
			}
		}
		return sb.toString();
	}
}

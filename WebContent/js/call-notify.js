/*
 * call-notify.js : notification des appels entrants sur toutes les pages (patient et medecin connectes)
 * et ouverture de la fenetre d'appel. Textes en ASCII (accents \uXXXX) : pages en ISO-8859-1.
 *
 * Fonctionnement : une connexion WebSocket "d'ecoute" par page. Elle recoit les appels entrants et affiche
 * le bandeau Accepter / Refuser. Accepter ouvre call.jsp, qui ouvre sa propre connexion et prend l'appel ;
 * l'appel se deroule donc dans une fenetre a part et ne coupe pas quand on navigue dans l'application.
 */
(function () {
	'use strict';

	var root = document.getElementById('callUi');
	if (!root || !window.WebSocket || !window.fetch) { return; }

	var ctx = root.getAttribute('data-ctx') || '';
	var wsBase = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host;
	var banner = document.getElementById('callBanner');
	var who = document.getElementById('callBannerWho');
	var toast = document.getElementById('callToast');
	var acceptBtn = document.getElementById('callAccept');
	var declineBtn = document.getElementById('callDecline');

	var ws = null;
	var attempts = 0;
	var stopped = false;
	var current = null;      // { callId, name, media }
	var heartbeat = null;
	var titleTimer = null;
	var baseTitle = document.title;

	// ------------------------------------------------------------ affichage

	function showBanner(call) {
		current = call;
		who.textContent = call.name || 'Correspondant';  // textContent : le nom est saisi par un utilisateur
		banner.hidden = false;
		if (!titleTimer) {
			var on = false;
			titleTimer = setInterval(function () {
				on = !on;
				document.title = on ? '\u260e Appel entrant' : baseTitle;
			}, 900);
		}
	}

	function hideBanner() {
		current = null;
		banner.hidden = true;
		if (titleTimer) { clearInterval(titleTimer); titleTimer = null; }
		document.title = baseTitle;
	}

	var toastTimer = null;
	function showToast(text) {
		toast.textContent = text;
		toast.hidden = false;
		if (toastTimer) { clearTimeout(toastTimer); }
		toastTimer = setTimeout(function () { toast.hidden = true; }, 8000);
	}

	// ------------------------------------------------------------ connexion

	function post(path) {
		return fetch(ctx + path, {
			method: 'POST',
			credentials: 'same-origin',
			headers: { 'X-Requested-With': 'XMLHttpRequest' }
		});
	}

	function connect() {
		if (stopped) { return; }
		post('/wsTicket').then(function (r) {
			if (r.status === 401 || r.status === 403 || r.status === 409) { stopped = true; throw new Error('auth'); }
			if (!r.ok) { throw new Error('ticket'); }
			return r.json();
		}).then(function (t) {
			var sock = new WebSocket(wsBase + ctx + '/ws/call?ticket=' + encodeURIComponent(t.ticket));
			ws = sock;
			sock.onopen = function () {
				attempts = 0;
				if (heartbeat) { clearInterval(heartbeat); }
				heartbeat = setInterval(function () {
					if (sock.readyState === 1) { sock.send('{"type":"ping"}'); }
				}, 30000);
			};
			sock.onmessage = function (e) { onMessage(e.data); };
			sock.onclose = function (e) {
				if (heartbeat) { clearInterval(heartbeat); heartbeat = null; }
				hideBanner();
				if (!stopped && e.code !== 1008) { retry(); }
			};
		}).then(null, function () {
			if (!stopped) { retry(); }
		});
	}

	function retry() {
		attempts++;
		var delay = Math.min(30000, 1000 * Math.pow(2, Math.min(attempts, 5)));
		setTimeout(connect, delay);
	}

	function onMessage(text) {
		var m;
		try { m = JSON.parse(text); } catch (e) { return; }
		if (!m || typeof m.type !== 'string') { return; }

		if (m.type === 'incoming') {
			showBanner({ callId: m.callId, name: m.fromName, media: m.media });
		} else if (m.type === 'taken') {
			if (current && current.callId === m.callId) { hideBanner(); }
		} else if (m.type === 'ended') {
			if (current && current.callId === m.callId) {
				var name = current.name || 'un correspondant';
				hideBanner();
				if (m.status === 'MISSED' || m.status === 'CANCELLED') { showToast('Appel manqu\u00e9 de ' + name); }
			}
		}
	}

	// ------------------------------------------------------------ actions

	function openCallWindow(query) {
		var url = ctx + '/call.jsp?' + query;
		var win = window.open(url, 'call', 'width=420,height=640,resizable=yes');
		if (!win) { window.location.href = url; }  // fenetre bloquee : on utilise l'onglet courant
	}

	acceptBtn.addEventListener('click', function () {
		if (!current) { return; }
		var c = current;
		hideBanner();
		openCallWindow('mode=incoming&callId=' + encodeURIComponent(c.callId) + '&media=AUDIO&peer=' + encodeURIComponent(c.name || ''));
	});

	declineBtn.addEventListener('click', function () {
		if (!current) { return; }
		if (ws && ws.readyState === 1) { ws.send(JSON.stringify({ type: 'reject', callId: current.callId })); }
		hideBanner();
	});

	// Boutons "Appeler" des pages : <button data-call-to="DOCTOR:5" data-call-media="AUDIO" data-call-name="...">
	document.addEventListener('click', function (e) {
		var el = e.target && e.target.closest ? e.target.closest('[data-call-to]') : null;
		if (!el) { return; }
		var parts = String(el.getAttribute('data-call-to')).split(':');
		if (parts.length !== 2 || (parts[0] !== 'USER' && parts[0] !== 'DOCTOR') || !/^[0-9]{1,9}$/.test(parts[1])) { return; }
		e.preventDefault();
		openCallWindow('mode=outgoing&toRole=' + parts[0] + '&toId=' + parts[1] + '&media=AUDIO&peer=' + encodeURIComponent(el.getAttribute('data-call-name') || ''));
	});

	connect();
})();

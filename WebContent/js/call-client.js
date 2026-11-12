/*
 * call-client.js : logique d'un appel vocal (signalisation WebSocket + WebRTC).
 * Aucune dependance a la page : tout ce qui touche au navigateur est injecte (voir les options),
 * ce qui permet de tester cette logique hors navigateur.
 *
 * Les textes sont en ASCII (accents ecrits \uXXXX) car les pages de l'application sont en ISO-8859-1.
 */
(function (root, factory) {
	if (typeof module === 'object' && module.exports) {
		module.exports = factory();
	} else {
		root.CallClient = factory();
	}
})(this, function () {
	'use strict';

	var ENDED_TEXT = {
		ENDED: 'Appel termin\u00e9',
		REJECTED: 'Appel refus\u00e9',
		CANCELLED: 'Appel annul\u00e9',
		MISSED: 'Pas de r\u00e9ponse',
		DROPPED: 'Connexion interrompue',
		LOST: 'Connexion perdue'
	};

	var ERROR_TEXT = {
		MIC_DENIED: 'Microphone inaccessible. Autorisez-le dans le navigateur puis relancez l\u2019appel.',
		NOT_ALLOWED: 'Vous ne pouvez pas appeler cette personne (aucun rendez-vous en commun).',
		RATE_LIMITED: 'Trop de tentatives d\u2019appel. R\u00e9essayez dans une minute.',
		BUSY_SELF: 'Vous \u00eates d\u00e9j\u00e0 en communication.',
		CALL_UNAVAILABLE: 'Cet appel n\u2019est plus disponible.',
		SERVER_ERROR: 'Service d\u2019appel momentan\u00e9ment indisponible.',
		AUTH: 'Session expir\u00e9e : reconnectez-vous.',
		BAD_LINK: 'Lien d\u2019appel invalide.'
	};

	var UNAVAILABLE_TEXT = {
		OFFLINE: 'Votre correspondant n\u2019est pas connect\u00e9.',
		BUSY: 'Votre correspondant est d\u00e9j\u00e0 en communication.'
	};

	var RECONNECT_ATTEMPTS = 10;
	var RECONNECT_DELAY_MS = 1000;
	var HANGUP_CONFIRM_TIMEOUT_MS = 2500;

	/**
	 * opts :
	 *   ctx          chemin de l'application ('' ou '/GESTIONHOPITAL')
	 *   wsBase       'ws://hote:port' ou 'wss://hote'
	 *   mode         'outgoing' | 'incoming'
	 *   toRole,toId  (sortant) correspondant
	 *   callId       (entrant) appel a accepter
	 *   media        'AUDIO'
	 *   deps         { fetch, WebSocket, RTCPeerConnection, getUserMedia, setTimeout, clearTimeout, setInterval, clearInterval }
	 *   ui           { state(name, info), remoteStream(stream), tick(seconds) }
	 */
	function CallClient(opts) {
		var self = this;
		var d = opts.deps;
		var ui = opts.ui;

		var ws = null;
		var pc = null;
		var localStream = null;
		var iceServers = [{ urls: ['stun:stun.l.google.com:19302'] }];
		var callId = 0;
		var accepted = false;
		var finished = false;
		var started = false;
		var initialDone = false;
		var isCaller = opts.mode === 'outgoing';
		var pendingCandidates = [];
		var remoteSet = false;
		var timerHandle = null;
		var heartbeat = null;
		var seconds = 0;
		var reconnects = 0;
		var hangupTimer = null;
		var muted = false;

		self._state = 'starting';

		function setState(name, info) {
			self._state = name;
			if (ui && ui.state) { ui.state(name, info || {}); }
		}

		function send(obj) {
			if (ws && ws.readyState === 1) {
				ws.send(JSON.stringify(obj));
				return true;
			}
			return false;
		}

		function fail(code) {
			if (finished) { return; }
			finished = true;
			cleanup();
			setState('error', { code: code, text: ERROR_TEXT[code] || ERROR_TEXT.SERVER_ERROR });
		}

		function finish(status, text) {
			if (finished) { return; }
			finished = true;
			cleanup();
			setState('ended', { status: status, text: text || ENDED_TEXT[status] || ENDED_TEXT.ENDED, seconds: seconds });
		}

		function cleanup() {
			if (timerHandle) { d.clearInterval(timerHandle); timerHandle = null; }
			if (heartbeat) { d.clearInterval(heartbeat); heartbeat = null; }
			if (hangupTimer) { d.clearTimeout(hangupTimer); hangupTimer = null; }
			if (localStream) {
				var tracks = localStream.getTracks();
				for (var i = 0; i < tracks.length; i++) { tracks[i].stop(); }
			}
			if (pc) { try { pc.close(); } catch (e) { /* deja ferme */ } pc = null; }
			if (ws) {
				try { ws.onclose = null; ws.close(1000); } catch (e) { /* deja ferme */ }
			}
		}

		// ------------------------------------------------------------ demarrage

		self.start = function () {
			if (started) { return; }
			started = true;
			setState('starting', { text: 'Pr\u00e9paration du microphone\u2026' });
			d.getUserMedia({ audio: true, video: false }).then(function (stream) {
				localStream = stream;
				return loadIceServers();
			}).then(function () {
				connect();
			}, function () {
				fail('MIC_DENIED');
			});
		};

		function loadIceServers() {
			return d.fetch(opts.ctx + '/iceConfig', {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'X-Requested-With': 'XMLHttpRequest' }
			}).then(function (r) {
				if (r.status === 401 || r.status === 403 || r.status === 409) { throw new Error('auth'); }
				return r.ok ? r.json() : null;
			}).then(function (cfg) {
				if (cfg && cfg.iceServers && cfg.iceServers.length) { iceServers = cfg.iceServers; }
			}, function (e) {
				if (e && e.message === 'auth') { throw e; }
				// configuration ICE indisponible : on garde le STUN par defaut
			}).then(null, function () {
				fail('AUTH');
				throw new Error('stop');
			});
		}

		function connect() {
			if (finished) { return; }
			d.fetch(opts.ctx + '/wsTicket', {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'X-Requested-With': 'XMLHttpRequest' }
			}).then(function (r) {
				if (!r.ok) { throw new Error('ticket ' + r.status); }
				return r.json();
			}).then(function (t) {
				if (finished) { return; }
				var sock = new d.WebSocket(opts.wsBase + opts.ctx + '/ws/call?ticket=' + encodeURIComponent(t.ticket));
				ws = sock;
				sock.onmessage = function (e) { onMessage(e.data); };
				sock.onclose = function () { onSocketClosed(sock); };
				sock.onopen = function () {
					if (heartbeat) { d.clearInterval(heartbeat); }
					heartbeat = d.setInterval(function () { send({ type: 'ping' }); }, 30000);
				};
			}).then(null, function () {
				retryOrFail();
			});
		}

		function onSocketClosed(sock) {
			if (finished || sock !== ws) { return; }
			if (heartbeat) { d.clearInterval(heartbeat); heartbeat = null; }
			if (callId && accepted) {
				retryOrFail();
			} else if (callId || initialDone) {
				finish('LOST');
			} else {
				retryOrFail();
			}
		}

		function retryOrFail() {
			if (finished) { return; }
			if (reconnects >= RECONNECT_ATTEMPTS) {
				if (callId && accepted) { finish('DROPPED'); } else { fail('SERVER_ERROR'); }
				return;
			}
			reconnects++;
			if (accepted) { setState('reconnecting', { text: 'Reconnexion\u2026' }); }
			d.setTimeout(connect, RECONNECT_DELAY_MS);
		}

		// ------------------------------------------------------------ messages du serveur

		function onMessage(text) {
			var m;
			try { m = JSON.parse(text); } catch (e) { return; }
			if (finished || !m || typeof m.type !== 'string') { return; }

			if (m.type === 'ready') { onReady(m); }
			else if (m.type === 'ringing') { callId = m.callId; setState('ringing-out', { text: 'Appel en cours\u2026' }); }
			else if (m.type === 'unavailable') { finish('UNAVAILABLE', UNAVAILABLE_TEXT[m.reason] || ENDED_TEXT.MISSED); }
			else if (m.type === 'accepted') { onAccepted(m); }
			else if (m.type === 'offer') { onOffer(m); }
			else if (m.type === 'answer') { onAnswer(m); }
			else if (m.type === 'ice') { onRemoteIce(m); }
			else if (m.type === 'peer_offline') { if (accepted) { setState('peer-offline', { text: 'Le correspondant a perdu la connexion\u2026' }); } }
			else if (m.type === 'peer_back') { if (isCaller && pc) { restartIce(); } backToCall(); }
			else if (m.type === 'resumed') { if (m.peerOnline) { if (isCaller && pc) { restartIce(); } backToCall(); } else { setState('peer-offline', { text: 'Le correspondant a perdu la connexion\u2026' }); } }
			else if (m.type === 'ended') { finish(m.status); }
			else if (m.type === 'error') { onServerError(m); }
		}

		function onReady(m) {
			if (!initialDone) {
				initialDone = true;
				if (isCaller) {
					if (m.callState !== 'NONE') { fail('BUSY_SELF'); return; }
					setState('starting', { text: 'Appel\u2026' });
					send({ type: 'call', toRole: opts.toRole, toId: opts.toId, media: 'AUDIO' });
				} else {
					if (m.callState === 'INCOMING' && m.callId === opts.callId) {
						callId = m.callId;
						setState('connecting', { text: 'Connexion\u2026' });
						send({ type: 'accept', callId: callId });
					} else {
						fail('CALL_UNAVAILABLE');
					}
				}
				return;
			}
			// reconnexion pendant l'appel
			if (callId && accepted) {
				if (m.callState === 'ACTIVE' && m.callId === callId) {
					reconnects = 0;
					send({ type: 'resume', callId: callId });
				} else {
					finish('DROPPED');
				}
			}
		}

		function onServerError(m) {
			var code = m.code;
			if (code === 'PEER_UNAVAILABLE') { return; }  // coupure momentanee du correspondant
			if (code === 'BAD_STATE' || code === 'UNKNOWN_CALL') {
				if (!accepted) { fail('CALL_UNAVAILABLE'); }
				return;
			}
			if (code === 'NOT_ALLOWED' || code === 'RATE_LIMITED' || code === 'BUSY_SELF') { fail(code); return; }
			if (code === 'SERVER_ERROR') { fail('SERVER_ERROR'); }
		}

		// ------------------------------------------------------------ WebRTC

		function ensurePc() {
			if (pc) { return; }
			pc = new d.RTCPeerConnection({ iceServers: iceServers });
			var tracks = localStream.getTracks();
			for (var i = 0; i < tracks.length; i++) { pc.addTrack(tracks[i], localStream); }

			pc.onicecandidate = function (e) {
				if (e.candidate && callId) {
					send({ type: 'ice', callId: callId, candidate: JSON.stringify(e.candidate) });
				}
			};
			pc.ontrack = function (e) {
				if (ui && ui.remoteStream && e.streams && e.streams[0]) { ui.remoteStream(e.streams[0]); }
			};
			pc.onconnectionstatechange = function () {
				if (!pc) { return; }
				var s = pc.connectionState;
				if (s === 'connected') { onMediaConnected(); }
				else if (s === 'disconnected') { setState('reconnecting', { text: 'Connexion instable\u2026' }); }
				else if (s === 'failed') { if (isCaller) { restartIce(); } else { setState('reconnecting', { text: 'Connexion perdue, nouvelle tentative\u2026' }); } }
			};
		}

		function pcConnected() { return !!pc && pc.connectionState === 'connected'; }

		function backToCall() { setState(pcConnected() ? 'active' : 'connecting', { text: pcConnected() ? 'En communication' : 'Connexion\u2026' }); }

		function onMediaConnected() {
			setState('active', { text: 'En communication' });
			if (!timerHandle) {
				timerHandle = d.setInterval(function () {
					seconds++;
					if (ui && ui.tick) { ui.tick(seconds); }
				}, 1000);
			}
		}

		function onAccepted(m) {
			callId = m.callId;
			accepted = true;
			reconnects = 0;
			setState('connecting', { text: 'Connexion\u2026' });
			ensurePc();
			if (isCaller) { makeOffer(false); }
		}

		function makeOffer(iceRestart) {
			pc.createOffer(iceRestart ? { iceRestart: true } : undefined).then(function (offer) {
				return pc.setLocalDescription(offer);
			}).then(function () {
				send({ type: 'offer', callId: callId, sdp: pc.localDescription.sdp });
			}).then(null, function () { fail('SERVER_ERROR'); });
		}

		function restartIce() { if (pc && !finished) { makeOffer(true); } }

		function onOffer(m) {
			if (!localStream) { return; }
			ensurePc();
			remoteSet = false;
			pc.setRemoteDescription({ type: 'offer', sdp: m.sdp }).then(function () {
				remoteSet = true;
				return drainCandidates();
			}).then(function () {
				return pc.createAnswer();
			}).then(function (answer) {
				return pc.setLocalDescription(answer);
			}).then(function () {
				send({ type: 'answer', callId: callId, sdp: pc.localDescription.sdp });
			}).then(null, function () { fail('SERVER_ERROR'); });
		}

		function onAnswer(m) {
			if (!pc) { return; }
			pc.setRemoteDescription({ type: 'answer', sdp: m.sdp }).then(function () {
				remoteSet = true;
				return drainCandidates();
			}).then(null, function () { fail('SERVER_ERROR'); });
		}

		function onRemoteIce(m) {
			var cand;
			try { cand = JSON.parse(m.candidate); } catch (e) { return; }
			if (!cand || typeof cand !== 'object') { return; }
			if (pc && remoteSet) {
				pc.addIceCandidate(cand).then(null, function () { /* candidat inutilisable : ignore */ });
			} else {
				pendingCandidates.push(cand);  // arrive avant la description distante
			}
		}

		function drainCandidates() {
			var list = pendingCandidates;
			pendingCandidates = [];
			var chain = Promise.resolve();
			list.forEach(function (c) {
				chain = chain.then(function () { return pc.addIceCandidate(c); }).then(null, function () { /* ignore */ });
			});
			return chain;
		}

		// ------------------------------------------------------------ actions de l'utilisateur

		self.toggleMute = function () {
			muted = !muted;
			if (localStream) {
				var tracks = localStream.getTracks();
				for (var i = 0; i < tracks.length; i++) { tracks[i].enabled = !muted; }
			}
			return muted;
		};

		self.hangup = function () {
			if (finished) { return; }
			var type = (!accepted && !isCaller) ? 'reject' : 'hangup';
			if (callId && send({ type: type, callId: callId })) {
				// on attend la confirmation "ended" du serveur ; sinon on termine localement
				hangupTimer = d.setTimeout(function () { finish(accepted ? 'ENDED' : 'CANCELLED'); }, HANGUP_CONFIRM_TIMEOUT_MS);
			} else {
				finish(accepted ? 'ENDED' : 'CANCELLED');
			}
		};

		/** A appeler quand la fenetre se ferme : evite de laisser l'appel sonner ou continuer dans le vide. */
		self.leave = function () {
			if (finished) { return; }
			if (callId) { send({ type: (!accepted && !isCaller) ? 'reject' : 'hangup', callId: callId }); }
			finished = true;
			cleanup();
		};

		self.state = function () { return self._state; };
		self.callId = function () { return callId; };
		self.seconds = function () { return seconds; };
		self._socket = function () { return ws; };
	}

	CallClient.ENDED_TEXT = ENDED_TEXT;
	CallClient.ERROR_TEXT = ERROR_TEXT;
	return CallClient;
});

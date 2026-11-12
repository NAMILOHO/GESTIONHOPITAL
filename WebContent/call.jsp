<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
	pageEncoding="ISO-8859-1"%>
<%@page isELIgnored="false"%>
<%
	boolean isPatient = session.getAttribute("userObj") != null;
	boolean isDoctor = session.getAttribute("doctObj") != null;
	if (isPatient == isDoctor) {
		response.sendRedirect(request.getContextPath() + "/index.jsp");
		return;
	}
	String theme = isPatient ? "success" : "primary";
	String ctxPath = request.getContextPath();
%>
<!DOCTYPE html>
<html>
<head>
<meta charset="ISO-8859-1">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Appel vocal - Medi Home</title>
<%@include file="component/allcss.jsp"%>
<style type="text/css">
body {
	background: #f4f6f8;
}
.call-card {
	max-width: 420px;
	margin: 24px auto;
}
.call-avatar {
	font-size: 6rem;
}
</style>
</head>
<body>
	<div class="container">
		<div class="card call-card shadow text-center">
			<div class="card-header bg-<%=theme%> text-white">
				<i class="fas fa-clinic-medical"></i> Medi Home &ndash; Appel vocal
			</div>
			<div class="card-body py-4">
				<div class="call-avatar text-<%=theme%>">
					<i class="fas fa-user-circle"></i>
				</div>
				<h4 id="peerName" class="mt-2 text-break"></h4>
				<p id="statusText" class="text-muted mb-1" aria-live="polite">Pr&eacute;paration&hellip;</p>
				<p id="timer" class="fs-3 fw-bold mb-0" hidden>00:00</p>
				<div id="errorBox" class="alert alert-danger mt-3 mb-0" role="alert" hidden></div>
			</div>
			<div class="card-footer d-flex justify-content-center gap-3 py-3">
				<button type="button" id="muteBtn"
					class="btn btn-outline-secondary btn-lg rounded-circle"
					title="Couper le micro" aria-label="Couper le micro" disabled>
					<i class="fas fa-microphone"></i>
				</button>
				<button type="button" id="hangupBtn"
					class="btn btn-danger btn-lg rounded-circle" title="Raccrocher"
					aria-label="Raccrocher">
					<i class="fas fa-phone-slash"></i>
				</button>
				<button type="button" id="closeBtn" class="btn btn-secondary" hidden>Fermer</button>
			</div>
		</div>
		<audio id="remoteAudio" autoplay></audio>
	</div>

	<script src="<%=ctxPath%>/js/call-client.js"></script>
	<script>
		(function() {
			var q = new URLSearchParams(location.search);
			var mode = q.get('mode');
			var toRole = q.get('toRole');
			var toId = parseInt(q.get('toId'), 10);
			var callId = parseInt(q.get('callId'), 10);
			var peer = (q.get('peer') || '').slice(0, 100);

			var statusText = document.getElementById('statusText');
			var timerEl = document.getElementById('timer');
			var errorBox = document.getElementById('errorBox');
			var muteBtn = document.getElementById('muteBtn');
			var hangupBtn = document.getElementById('hangupBtn');
			var closeBtn = document.getElementById('closeBtn');
			var audio = document.getElementById('remoteAudio');

			document.getElementById('peerName').textContent = peer || 'Correspondant';

			function showError(text) {
				errorBox.textContent = text;
				errorBox.hidden = false;
				statusText.hidden = true;
				muteBtn.hidden = true;
				hangupBtn.hidden = true;
				closeBtn.hidden = false;
			}

			var validLink = (mode === 'outgoing' && (toRole === 'USER' || toRole === 'DOCTOR') && toId > 0)
					|| (mode === 'incoming' && callId > 0);
			if (!validLink) {
				showError(CallClient.ERROR_TEXT.BAD_LINK);
			} else if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.RTCPeerConnection) {
				showError('Appel impossible : le navigateur n\u2019autorise le microphone que sur une page s\u00e9curis\u00e9e (HTTPS ou localhost).');
			} else {
				var client = new CallClient({
					ctx : '<%=ctxPath%>',
					wsBase : (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host,
					mode : mode,
					toRole : toRole,
					toId : toId,
					callId : callId,
					media : 'AUDIO',
					deps : {
						fetch : window.fetch.bind(window),
						WebSocket : window.WebSocket,
						RTCPeerConnection : window.RTCPeerConnection,
						getUserMedia : function(c) { return navigator.mediaDevices.getUserMedia(c); },
						setTimeout : function(f, t) { return window.setTimeout(f, t); },
						clearTimeout : function(h) { window.clearTimeout(h); },
						setInterval : function(f, t) { return window.setInterval(f, t); },
						clearInterval : function(h) { window.clearInterval(h); }
					},
					ui : {
						state : function(name, info) {
							if (name === 'active') {
								statusText.textContent = 'En communication';
								timerEl.hidden = false;
								muteBtn.disabled = false;
							} else if (name === 'ended') {
								statusText.textContent = info.text;
								muteBtn.hidden = true;
								hangupBtn.hidden = true;
								closeBtn.hidden = false;
								window.setTimeout(function() { window.close(); }, 6000);
							} else if (name === 'error') {
								showError(info.text);
							} else if (info && info.text) {
								statusText.textContent = info.text;
							}
						},
						remoteStream : function(stream) {
							audio.srcObject = stream;
							var p = audio.play();
							if (p && p.catch) { p.catch(function() { }); }
						},
						tick : function(s) {
							var m = Math.floor(s / 60), r = s % 60;
							timerEl.textContent = (m < 10 ? '0' : '') + m + ':' + (r < 10 ? '0' : '') + r;
						}
					}
				});

				muteBtn.addEventListener('click', function() {
					var muted = client.toggleMute();
					muteBtn.firstElementChild.className = muted ? 'fas fa-microphone-slash' : 'fas fa-microphone';
					muteBtn.title = muted ? 'R\u00e9activer le micro' : 'Couper le micro';
					muteBtn.setAttribute('aria-label', muteBtn.title);
					muteBtn.classList.toggle('btn-secondary', muted);
					muteBtn.classList.toggle('btn-outline-secondary', !muted);
				});
				hangupBtn.addEventListener('click', function() {
					hangupBtn.disabled = true;
					client.hangup();
				});
				window.addEventListener('pagehide', function() { client.leave(); });
				client.start();
			}

			closeBtn.addEventListener('click', function() {
				window.close();
				// fenetre non ouverte par un script : retour a l'accueil
				window.setTimeout(function() { window.location.href = '<%=ctxPath%>/index.jsp'; }, 300);
			});
		})();
	</script>
</body>
</html>

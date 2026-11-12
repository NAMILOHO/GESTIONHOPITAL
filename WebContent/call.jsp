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
<title>Appel - Medi Home</title>
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
.video-stage {
	position: relative;
	width: 100%;
	height: 340px;
	background: #111;
	border-radius: 8px;
	overflow: hidden;
}
.video-stage[hidden] {
	display: none !important;
}
.video-stage video {
	display: block;
}
#remoteVideo {
	width: 100%;
	height: 100%;
	object-fit: cover;
	visibility: hidden;
}
#remotePlaceholder {
	position: absolute;
	top: 0;
	left: 0;
	right: 0;
	bottom: 0;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	color: #bbb;
}
#remotePlaceholder[hidden] {
	display: none !important;
}
#remotePlaceholder i {
	font-size: 5rem;
}
#localVideo {
	position: absolute;
	right: 8px;
	bottom: 8px;
	width: 96px;
	height: 128px;
	object-fit: cover;
	transform: scaleX(-1);
	background: #333;
	border: 2px solid #fff;
	border-radius: 6px;
}
#localVideo[hidden] {
	display: none !important;
}
</style>
</head>
<body>
	<div class="container">
		<div class="card call-card shadow text-center">
			<div class="card-header bg-<%=theme%> text-white">
				<i class="fas fa-clinic-medical"></i> Medi Home &ndash; <span id="callTitle">Appel vocal</span>
			</div>
			<div class="card-body py-4">
				<div id="videoStage" class="video-stage" hidden>
					<video id="remoteVideo" autoplay playsinline muted></video>
					<div id="remotePlaceholder">
						<i class="fas fa-user-circle"></i>
						<div>Pas de vid&eacute;o pour le moment</div>
					</div>
					<video id="localVideo" autoplay playsinline muted hidden></video>
				</div>
				<div id="avatarBox" class="call-avatar text-<%=theme%>">
					<i class="fas fa-user-circle"></i>
				</div>
				<h4 id="peerName" class="mt-2 text-break"></h4>
				<p id="statusText" class="text-muted mb-1" aria-live="polite">Pr&eacute;paration&hellip;</p>
				<p id="timer" class="fs-3 fw-bold mb-0" hidden>00:00</p>
				<p id="noticeText" class="text-warning small mt-2 mb-0" hidden></p>
				<div id="errorBox" class="alert alert-danger mt-3 mb-0" role="alert" hidden></div>
			</div>
			<div class="card-footer d-flex justify-content-center flex-wrap gap-3 py-3">
				<button type="button" id="muteBtn"
					class="btn btn-outline-secondary btn-lg rounded-circle"
					title="Couper le micro" aria-label="Couper le micro" disabled>
					<i class="fas fa-microphone"></i>
				</button>
				<button type="button" id="camBtn"
					class="btn btn-outline-secondary btn-lg rounded-circle"
					title="Couper la cam&eacute;ra" aria-label="Couper la cam&eacute;ra" hidden>
					<i class="fas fa-video"></i>
				</button>
				<button type="button" id="switchBtn"
					class="btn btn-outline-secondary btn-lg rounded-circle"
					title="Changer de cam&eacute;ra" aria-label="Changer de cam&eacute;ra" hidden>
					<i class="fas fa-sync-alt"></i>
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
			var media = q.get('media') === 'VIDEO' ? 'VIDEO' : 'AUDIO';
			var cam = q.get('cam') !== '0';               // false : reponse "audio seulement"
			var videoUi = media === 'VIDEO' && cam;       // affiche les videos

			var statusText = document.getElementById('statusText');
			var timerEl = document.getElementById('timer');
			var errorBox = document.getElementById('errorBox');
			var muteBtn = document.getElementById('muteBtn');
			var hangupBtn = document.getElementById('hangupBtn');
			var closeBtn = document.getElementById('closeBtn');
			var audio = document.getElementById('remoteAudio');
			var stage = document.getElementById('videoStage');
			var avatarBox = document.getElementById('avatarBox');
			var remoteVideo = document.getElementById('remoteVideo');
			var remotePlaceholder = document.getElementById('remotePlaceholder');
			var localVideo = document.getElementById('localVideo');
			var camBtn = document.getElementById('camBtn');
			var switchBtn = document.getElementById('switchBtn');
			var noticeEl = document.getElementById('noticeText');
			var remoteVideoId = '';

			document.getElementById('peerName').textContent = peer || 'Correspondant';
			if (media === 'VIDEO') { document.getElementById('callTitle').textContent = 'Appel vid\u00e9o'; }
			if (videoUi) { stage.hidden = false; avatarBox.hidden = true; }

			function showError(text) {
				errorBox.textContent = text;
				errorBox.hidden = false;
				statusText.hidden = true;
				muteBtn.hidden = true;
				camBtn.hidden = true;
				switchBtn.hidden = true;
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
					media : media,
					cam : cam,
					deps : {
						fetch : window.fetch.bind(window),
						WebSocket : window.WebSocket,
						RTCPeerConnection : window.RTCPeerConnection,
						getUserMedia : function(c) { return navigator.mediaDevices.getUserMedia(c); },
						enumerateDevices : function() { return navigator.mediaDevices.enumerateDevices(); },
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
								camBtn.hidden = true;
								switchBtn.hidden = true;
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
							if (audio.srcObject !== stream) { audio.srcObject = stream; }
							var p = audio.play();
							if (p && p.catch) { p.catch(function() { }); }
							if (videoUi) {
								var vt = stream.getVideoTracks();
								var vid = vt.length ? vt[0].id : '';
								if (vid !== remoteVideoId) {
									remoteVideoId = vid;
									remoteVideo.srcObject = vt.length ? new MediaStream(vt) : null;
								}
							}
						},
						localStream : function(stream, hasVideo) {
							if (!videoUi) { return; }
							if (hasVideo) {
								localVideo.srcObject = new MediaStream(stream.getVideoTracks());
								localVideo.hidden = false;
								camBtn.hidden = false;
							} else {
								localVideo.hidden = true;
								camBtn.hidden = true;
							}
						},
						remoteCam : function(on) {
							if (!videoUi) { return; }
							remoteVideo.style.visibility = on ? 'visible' : 'hidden';
							remotePlaceholder.hidden = on;
						},
						cameras : function(n) {
							switchBtn.hidden = !(videoUi && n > 1 && client.hasCamera());
						},
						notice : function(text) {
							noticeEl.textContent = text;
							noticeEl.hidden = false;
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
				camBtn.addEventListener('click', function() {
					var on = client.toggleCamera();
					camBtn.firstElementChild.className = on ? 'fas fa-video' : 'fas fa-video-slash';
					camBtn.title = on ? 'Couper la cam\u00e9ra' : 'Allumer la cam\u00e9ra';
					camBtn.setAttribute('aria-label', camBtn.title);
					camBtn.classList.toggle('btn-secondary', !on);
					camBtn.classList.toggle('btn-outline-secondary', on);
					localVideo.style.opacity = on ? '1' : '0.3';
				});
				switchBtn.addEventListener('click', function() {
					switchBtn.disabled = true;
					client.switchCamera().then(function() { switchBtn.disabled = false; });
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

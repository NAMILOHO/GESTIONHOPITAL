<%-- Notification d'appel entrant (inclus par les navbars patient et medecin). N'affiche rien si personne n'est connecte. --%>
<%
	boolean callUiPatient = session.getAttribute("userObj") != null;
	boolean callUiDoctor = session.getAttribute("doctObj") != null;
	if (callUiPatient != callUiDoctor) {
		String callUiTheme = callUiPatient ? "success" : "primary";
		String callUiCtx = request.getContextPath();
%>
<style type="text/css">
.call-banner {
	position: fixed;
	top: 70px;
	right: 16px;
	z-index: 2000;
	width: 330px;
	max-width: calc(100vw - 32px);
	background: #fff;
	border-radius: 10px;
	border-left: 6px solid var(--bs-<%=callUiTheme%>);
	box-shadow: 0 6px 24px rgba(0, 0, 0, .35);
	padding: 14px 16px;
}
.call-banner[hidden], #callToast[hidden] {
	display: none !important;
}
.call-banner .call-ring {
	animation: callPulse 1s infinite;
}
@keyframes callPulse {
	0%, 100% { transform: scale(1); }
	50% { transform: scale(1.25); }
}
#callToast {
	position: fixed;
	top: 70px;
	right: 16px;
	z-index: 2000;
	max-width: calc(100vw - 32px);
	padding: 10px 16px;
	border-radius: 8px;
	background: #333;
	color: #fff;
	box-shadow: 0 4px 16px rgba(0, 0, 0, .3);
}
</style>
<div id="callUi" data-ctx="<%=callUiCtx%>">
	<div id="callBanner" class="call-banner" role="alertdialog" aria-live="assertive" hidden>
		<div class="d-flex align-items-center mb-2">
			<i class="fas fa-phone-volume fs-3 me-3 text-<%=callUiTheme%> call-ring"></i>
			<div>
				<div class="fw-bold">Appel entrant</div>
				<div id="callBannerWho" class="text-break"></div>
			</div>
		</div>
		<div class="d-flex gap-2">
			<button type="button" id="callAccept" class="btn btn-success btn-sm flex-fill">
				<i class="fas fa-phone"></i> Accepter
			</button>
			<button type="button" id="callDecline" class="btn btn-danger btn-sm flex-fill">
				<i class="fas fa-phone-slash"></i> Refuser
			</button>
		</div>
	</div>
	<div id="callToast" role="status" hidden></div>
</div>
<script src="<%=callUiCtx%>/js/call-notify.js"></script>
<%
	}
%>

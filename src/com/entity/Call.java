package com.entity;

import java.sql.Timestamp;

public class Call {

	private long id;
	private CallRole callerRole;
	private int callerId;
	private CallRole calleeRole;
	private int calleeId;
	private CallType type;
	private CallStatus status;
	private Integer appointmentId;
	private Timestamp createdAt;
	private Timestamp answeredAt;
	private Timestamp endedAt;
	private Integer durationSec;

	public Call() {
		super();
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public CallRole getCallerRole() {
		return callerRole;
	}

	public void setCallerRole(CallRole callerRole) {
		this.callerRole = callerRole;
	}

	public int getCallerId() {
		return callerId;
	}

	public void setCallerId(int callerId) {
		this.callerId = callerId;
	}

	public CallRole getCalleeRole() {
		return calleeRole;
	}

	public void setCalleeRole(CallRole calleeRole) {
		this.calleeRole = calleeRole;
	}

	public int getCalleeId() {
		return calleeId;
	}

	public void setCalleeId(int calleeId) {
		this.calleeId = calleeId;
	}

	public CallType getType() {
		return type;
	}

	public void setType(CallType type) {
		this.type = type;
	}

	public CallStatus getStatus() {
		return status;
	}

	public void setStatus(CallStatus status) {
		this.status = status;
	}

	public Integer getAppointmentId() {
		return appointmentId;
	}

	public void setAppointmentId(Integer appointmentId) {
		this.appointmentId = appointmentId;
	}

	public Timestamp getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Timestamp createdAt) {
		this.createdAt = createdAt;
	}

	public Timestamp getAnsweredAt() {
		return answeredAt;
	}

	public void setAnsweredAt(Timestamp answeredAt) {
		this.answeredAt = answeredAt;
	}

	public Timestamp getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(Timestamp endedAt) {
		this.endedAt = endedAt;
	}

	public Integer getDurationSec() {
		return durationSec;
	}

	public void setDurationSec(Integer durationSec) {
		this.durationSec = durationSec;
	}

}

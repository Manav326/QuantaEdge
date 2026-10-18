package com.quantaedge.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a complete tutor turn atomically. Provider calls happen before this service is invoked,
 * so the database transaction is never held while waiting on model inference.
 */
@Service
public class TutorConversationWriter {
  private final JdbcTemplate jdbc;

  public TutorConversationWriter(JdbcTemplate jdbc) {
    this.jdbc=jdbc;
  }

  /**
   * Persists only when the session belongs to the same institution that authorized the caller.
   * The older overload remains for isolated legacy tests; learner HTTP controllers use this one.
   */
  @Transactional
  public void persistTurn(long sessionId, long institutionId, String mode, String userMessage, TutorReply reply) {
    int updated=jdbc.update(
        "update tutor_session set last_message_at=now() where id=? and institution_id=? and ended_at is null",
        sessionId,institutionId);
    if (updated!=1) {
      throw new TutorUnavailableException("Tutor session has ended or belongs to a different institution");
    }
    jdbc.update(
        "insert into tutor_message(session_id,role,mode,message) values(?,'USER',?,?)",
        sessionId,mode,userMessage);
    jdbc.update("""
        insert into tutor_message(session_id,role,mode,message,model,provider_response_id,input_tokens,output_tokens)
        values(?,'ASSISTANT',?,?,?,?,?,?)
        """,
        sessionId,mode,reply.text(),reply.model(),reply.providerResponseId(),
        reply.inputTokens(),reply.outputTokens());
  }

  @Transactional
  public void persistTurn(long sessionId, String mode, String userMessage, TutorReply reply) {
    int updated=jdbc.update(
        "update tutor_session set last_message_at=now() where id=? and ended_at is null", sessionId);
    if (updated!=1) {
      throw new TutorUnavailableException("Tutor session has ended; start a new session to continue");
    }

    jdbc.update(
        "insert into tutor_message(session_id,role,mode,message) values(?,'USER',?,?)",
        sessionId, mode, userMessage);
    jdbc.update("""
        insert into tutor_message(session_id,role,mode,message,model,provider_response_id,input_tokens,output_tokens)
        values(?,'ASSISTANT',?,?,?,?,?,?)
        """,
        sessionId, mode, reply.text(), reply.model(), reply.providerResponseId(),
        reply.inputTokens(), reply.outputTokens());
  }
}

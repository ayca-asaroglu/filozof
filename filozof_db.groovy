package Endpoint.idea

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import com.onresolve.scriptrunner.db.DatabaseUtil
import com.atlassian.jira.component.ComponentAccessor
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.BaseScript

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import java.util.UUID

@BaseScript CustomEndpointDelegate delegate

final String DB_POOL = "local"

/* -------------------------
   Helpers
   ------------------------- */
int qpInt(MultivaluedMap qp, String key, int defVal, int minVal, int maxVal) {
  def raw = qp.getFirst(key)
  int v
  try { v = (raw ?: defVal.toString()) as int } catch (ignored) { v = defVal }
  Math.max(minVal, Math.min(v, maxVal))
}

Response json(int status, Map payload) {
  Response.status(status).entity(new JsonBuilder(payload).toString()).build()
}

String clip(String s, int maxLen) {
  s ? (s.length() > maxLen ? s.substring(0, maxLen) : s) : null
}

/* =========================================================
   1) GET THREADS
   ========================================================= */
fibarprChatThreads(httpMethod: "GET") { MultivaluedMap qp, String body ->
  def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
  if (!user) return json(401, [ok:false])

  int offset = qpInt(qp, "offset", 0, 0, 1_000_000)
  int limit  = qpInt(qp, "limit", 20, 1, 100)

  def items = []
  DatabaseUtil.withSql(DB_POOL) { sql ->
    items = sql.rows("""
      SELECT 
        CAST(thread_id AS VARCHAR(36)) AS thread_id,
        title, last_snippet, last_message_at, updated_at, message_count
      FROM dbo.fibarpr_chat_thread
      WHERE user_key = ? AND is_deleted = 0
      ORDER BY last_message_at DESC
      OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
    """, [user.key, offset, limit])
  }

  json(200, [ok:true, items:items])
}

/* =========================================================
   2) GET MESSAGES
   ========================================================= */
fibarprChatMessages(httpMethod: "GET") {
  MultivaluedMap qp, String body, HttpServletRequest req ->

  def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
  if (!user) return json(401, [ok:false])

  def parts = (getAdditionalPath(req) ?: "").tokenize('/')
  if (parts.size() < 2 || parts[1] != "messages")
    return json(400, [ok:false])

  String threadId = parts[0]
  try { UUID.fromString(threadId) } catch (ignored) {
    return json(400, [ok:false, error:"Invalid threadId"])
  }

  int offset = qpInt(qp, "offset", 0, 0, 1_000_000)
  int limit  = qpInt(qp, "limit", 30, 1, 100)

  def allowed
  DatabaseUtil.withSql(DB_POOL) { sql ->
    allowed = sql.firstRow("""
      SELECT 1 FROM dbo.fibarpr_chat_thread
      WHERE thread_id = ? AND user_key = ? AND is_deleted = 0
    """, [threadId, user.key])
  }
  if (!allowed) return json(404, [ok:false])

  def items = []
  DatabaseUtil.withSql(DB_POOL) { sql ->
    items = sql.rows("""
      SELECT id, role, content, created_at
      FROM dbo.fibarpr_chat_message
      WHERE thread_id = ?
      ORDER BY id DESC
      OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
    """, [threadId, offset, limit])
  }

  json(200, [ok:true, items:items])
}

/* =========================================================
   3) POST CHAT
   ========================================================= */
fibarprChat(httpMethod: "POST") {
  MultivaluedMap qp, String body ->

  def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
  if (!user) return json(401, [ok:false])

  def payload = body ? new JsonSlurper().parseText(body) : [:]
  String question = payload?.question?.toString()?.trim()
  if (!question) return json(400, [ok:false])

  def threadId = payload?.thread_id
log.warn(threadId)
log.warn(threadId.getClass().toString())

  String answer = "Alındı: ${question}"

  DatabaseUtil.withSql(DB_POOL) { sql ->
    sql.connection.autoCommit = false
    try {

     if (!threadId) {
      log.warn("thread yok")
        def row = sql.firstRow("""
          INSERT INTO dbo.fibarpr_chat_thread (user_key, title, last_snippet)
          OUTPUT CAST(inserted.thread_id AS VARCHAR(36)) AS thread_id
          VALUES (?, ?, ?)
        """, [user.key, clip(question,60), clip(question,300)])
        threadId = (row.thread_id).toString()
        log.warn("thread oluştu")
      }

      sql.execute("""
        INSERT INTO dbo.fibarpr_chat_message (thread_id, role, content)
        VALUES (?, 'user', ?)
      """, [threadId, question])

      sql.execute("""
        INSERT INTO dbo.fibarpr_chat_message (thread_id, role, content)
        VALUES (?, 'assistant', ?)
      """, [threadId, answer])

      sql.execute("""
        UPDATE dbo.fibarpr_chat_thread
        SET message_count = message_count + 2,
            last_message_at = SYSUTCDATETIME(),
            updated_at = SYSUTCDATETIME(),
            last_snippet = ?
        WHERE thread_id = ?
      """, [clip(answer,300), threadId])

      sql.connection.commit()
    } catch (e) {
      sql.connection.rollback()
      throw e
    } finally {
      sql.connection.autoCommit = true
    }
  }

  json(200, [ok:true, thread_id:threadId, answer:answer])
}

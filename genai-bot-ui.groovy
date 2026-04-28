import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import com.onresolve.scriptrunner.db.DatabaseUtil
import com.atlassian.jira.component.ComponentAccessor
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.BaseScript
import groovy.transform.Field

import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response

@BaseScript CustomEndpointDelegate delegate

@Field static final String DB_POOL = "local"
@Field static final String CATEGORY = "ai_idea"

String clipStr(String s, int maxLen) {
    s ? (s.length() > maxLen ? s.substring(0, maxLen) : s) : null
}

aiIdeaChat(httpMethod: "POST") { MultivaluedMap queryParams, String body ->
  try {

    def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
    if (!user) {
        return Response.status(401)
            .entity(JsonOutput.toJson([error: "Unauthorized"]))
            .type("application/json;charset=UTF-8")
            .build()
    }

    def json = new JsonSlurper().parseText(body ?: "{}")

    String threadId   = json.thread_id?.toString()?.trim() ?: null
    String userMessage = json.message?.toString()?.trim()
    def chatHistory   = json.chat_history ?: []   // [{role, content}, ...] — UI'dan gelir

    if (!userMessage) {
        return Response.status(400)
            .entity(JsonOutput.toJson([error: "message alanı zorunludur"]))
            .type("application/json;charset=UTF-8")
            .build()
    }

    String azureEndpoint = "https://cog-xx.openai.azure.com/"
    String deploymentName = "gpt-4.1"
    String apiVersion = "2024-06-01"
    String apiKey = "xx"

    String systemPrompt = """
Sen bir AI fikir toplama asistanısın.

Görevin, kullanıcıdan AI geliştirme fikrini anlamak için aşağıdaki 5 sorunun tamamına net cevap almaktır.
Eksik, belirsiz veya yüzeysel cevap varsa function call yapma; sadece eksik alanı doğal ve kısa şekilde sor.

Zorunlu 5 alan:
1. fikir_adi: AI fikrinin kısa adı veya başlığı
2. problem: Bu fikir hangi problemi çözüyor?
3. hedef_kitle: Kimler kullanacak veya kimler etkilenecek?
4. cozum: AI nasıl çalışacak, ne yapacak?
5. beklenen_fayda: İşe, müşteriye veya kullanıcıya beklenen katkı nedir?

Kurallar:
- Aynı anda en fazla 1 soru sor.
- Kullanıcının verdiği cevaplardan alanları çıkar ve konuşma boyunca hatırla.
- Kullanıcı birden fazla alanı tek cevapta verirse hepsini işle.
- Tüm 5 alan dolmadan kesinlikle özet çıkarma.
- Tüm alanlar tamamlandığında sadece submit_ai_idea function/tool çağır.
- Function arguments içinde 5 alanın tamamı dolu olmalı.
- Eksik alan için tahmin üretme.
- Cevapları kurumsal, sade ve anlaşılır Türkçe ile normalize et.
- Kullanıcı konu dışına çıkarsa kibarca fikir formuna geri yönlendir.
""".trim()

    def tools = [[
        type: "function",
        function: [
            name: "submit_ai_idea",
            description: "Kullanıcının AI geliştirme fikri için zorunlu 5 alan tamamlandığında fikri özet formatına dönüştürür.",
            parameters: [
                type: "object",
                additionalProperties: false,
                properties: [
                    fikir_adi     : [type: "string", description: "AI fikrinin kısa adı veya başlığı"],
                    problem       : [type: "string", description: "Çözülmek istenen problem"],
                    hedef_kitle   : [type: "string", description: "Fikrin hedef kullanıcıları veya etkilenen kitlesi"],
                    cozum         : [type: "string", description: "AI çözümünün nasıl çalışacağı ve ne yapacağı"],
                    beklenen_fayda: [type: "string", description: "Beklenen iş, müşteri veya kullanıcı faydası"]
                ],
                required: ["fikir_adi", "problem", "hedef_kitle", "cozum", "beklenen_fayda"]
            ]
        ]
    ]]

    // Mesaj listesi: system + UI'dan gelen geçmiş + yeni kullanıcı mesajı
    def messages = [[role: "system", content: systemPrompt]]
    chatHistory.each { m ->
        String role    = m.role?.toString()?.trim()
        String content = m.content?.toString()?.trim()
        if (role && content) messages << [role: role, content: content]
    }
    messages << [role: "user", content: userMessage]

    def requestPayload = [
        messages   : messages,
        tools      : tools,
        tool_choice: "auto",
        temperature: 0.2
    ]

    URL url = new URL("${azureEndpoint}/openai/deployments/${deploymentName}/chat/completions?api-version=${apiVersion}")
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("api-key", apiKey)
    conn.setDoOutput(true)

    conn.outputStream.withWriter("UTF-8") { writer ->
        writer << JsonOutput.toJson(requestPayload)
    }

    int status = conn.responseCode
    String responseText = status >= 200 && status < 300 ?
        conn.inputStream.getText("UTF-8") :
        conn.errorStream?.getText("UTF-8")

    if (status < 200 || status >= 300) {
        return Response.status(status)
            .entity(JsonOutput.toJson([error: "Azure OpenAI hatası", detail: responseText]))
            .type("application/json;charset=UTF-8")
            .build()
    }

    def azureResponse    = new JsonSlurper().parseText(responseText)
    def assistantMessage = azureResponse.choices[0].message
    def toolCalls        = assistantMessage.tool_calls

    String  assistantContent
    boolean completed = false
    def     ideaArgs  = null
    String  summary   = null

    if (toolCalls && toolCalls.size() > 0) {
        def toolCall = toolCalls[0]
        if (toolCall.function.name == "submit_ai_idea") {
            ideaArgs = new JsonSlurper().parseText(toolCall.function.arguments)
            summary = """
AI Fikri Özeti

Fikir Adı:
${ideaArgs.fikir_adi}

Problem:
${ideaArgs.problem}

Hedef Kitle:
${ideaArgs.hedef_kitle}

Çözüm:
${ideaArgs.cozum}

Beklenen Fayda:
${ideaArgs.beklenen_fayda}
""".trim()
            assistantContent = summary
            completed = true
        }
    } else {
        assistantContent = assistantMessage.content
    }

    // DB'ye kaydet — thread yoksa yeni oluştur
    DatabaseUtil.withSql(DB_POOL) { sql ->
        sql.connection.autoCommit = false
        try {
            if (!threadId) {
                def row = sql.firstRow("""
                    INSERT INTO dbo.fibarpr_chat_thread (user_key, category, title, last_snippet)
                    OUTPUT CAST(inserted.thread_id AS VARCHAR(36)) AS thread_id
                    VALUES (?, ?, ?, ?)
                """, [user.key, CATEGORY, clipStr(userMessage, 60), clipStr(userMessage, 300)])
                threadId = row.thread_id.toString()
            }

            sql.execute("""
                INSERT INTO dbo.fibarpr_chat_message (thread_id, category, role, content)
                VALUES (?, ?, 'user', ?)
            """, [threadId, CATEGORY, userMessage])

            sql.execute("""
                INSERT INTO dbo.fibarpr_chat_message (thread_id, category, role, content)
                VALUES (?, ?, 'assistant', ?)
            """, [threadId, CATEGORY, assistantContent])

            sql.execute("""
                UPDATE dbo.fibarpr_chat_thread
                SET message_count    = message_count + 2,
                    last_message_at  = SYSUTCDATETIME(),
                    updated_at       = SYSUTCDATETIME(),
                    last_snippet     = ?
                WHERE thread_id = ?
            """, [clipStr(assistantContent, 300), threadId])

            sql.connection.commit()
        } catch (Exception e) {
            sql.connection.rollback()
            throw e
        } finally {
            sql.connection.autoCommit = true
        }
    }

    if (completed) {
        return Response.ok(JsonOutput.toJson([
            thread_id: threadId,
            completed: true,
            idea     : ideaArgs,
            summary  : summary
        ]))
        .type("application/json;charset=UTF-8")
        .build()
    }

    return Response.ok(JsonOutput.toJson([
        thread_id: threadId,
        completed: false,
        message  : assistantContent
    ]))
    .type("application/json;charset=UTF-8")
    .build()

  } catch (Exception e) {
    return Response.status(500)
        .entity(JsonOutput.toJson([error: "Sunucu hatası: ${e.message}"]))
        .type("application/json;charset=UTF-8")
        .build()
  }
}

aiIdeaChatbotPage(httpMethod: "GET") { MultivaluedMap queryParams ->

    String html = """
<!DOCTYPE html>
<html lang="tr">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>AI Fikir Toplama</title>
  <style>
    :root {
      --primary:      #2F5DA9;
      --primary-dark: #0043A6;
      --primary-light:#4C9AFF;
      --bg:           #F7F8FA;
      --surface:      #ffffff;
      --border:       #DFE1E6;
      --text:         #172B4D;
      --text-muted:   #42526E;
      --text-subtle:  #6B778C;
      --focus-ring:   rgba(76,154,255,0.2);
      --shadow-sm:    0 1px 3px rgba(0,0,0,0.10);
      --radius:       4px;
      --radius-lg:    8px;
    }

    *  { box-sizing: border-box; margin: 0; padding: 0; }
    html, body { height: 100%; font-size: 14px; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      overflow: hidden;
    }

    /* ===== Layout ===== */
    .app  { display: flex; flex-direction: column; height: 100%; }

    .header {
      height: 65px;
      display: flex; align-items: center;
      padding: 0 30px;
      background: var(--primary);
      gap: 12px;
      box-shadow: 0 2px 4px rgba(0,0,0,0.1);
      flex-shrink: 0;
    }
    .header-title {
      font-size: 18px; font-weight: 600; color: #fff; flex: 1;
    }
    .header-subtitle {
      font-size: 12px; color: rgba(255,255,255,0.75); margin-top: 2px;
    }
    .btn-new {
      height: 34px; padding: 0 14px;
      border-radius: var(--radius);
      border: 1px solid rgba(255,255,255,0.35);
      background: rgba(255,255,255,0.12);
      color: rgba(255,255,255,0.95);
      font-size: 13px; font-weight: 600;
      cursor: pointer;
      transition: background .15s, border-color .15s;
    }
    .btn-new:hover {
      background: rgba(255,255,255,0.22);
      border-color: rgba(255,255,255,0.5);
    }

    /* ===== Chat area ===== */
    .chat-wrap {
      flex: 1;
      overflow-y: auto;
      display: flex;
      justify-content: center;
      padding: 8px 0;
      background: var(--bg);
    }
    .chat-wrap::-webkit-scrollbar { width: 6px; }
    .chat-wrap::-webkit-scrollbar-thumb {
      background: rgba(100,116,139,.18); border-radius: 999px;
    }
    .chat {
      width: min(720px, 100%);
      padding: 32px 24px 16px;
      display: flex; flex-direction: column; gap: 0;
    }

    /* ===== Message rows ===== */
    @keyframes msgIn {
      from { opacity: 0; transform: translateY(6px); }
      to   { opacity: 1; transform: translateY(0); }
    }
    .row {
      display: flex; gap: 10px; align-items: flex-start;
      animation: msgIn .18s ease-out both;
    }
    .row.user  { justify-content: flex-end; align-items: flex-end; }
    .row.group-start { margin-top: 28px; }
    .row.grouped     { margin-top: 4px; }
    .row.grouped .msg-avatar { visibility: hidden; }

    /* Avatars */
    .msg-avatar {
      width: 30px; height: 30px; border-radius: 50%;
      background: var(--primary); color: #fff;
      display: grid; place-items: center;
      font-size: 12px; font-weight: 700;
      flex-shrink: 0;
    }

    /* Bubbles */
    .bubble {
      padding: 10px 16px;
      border-radius: 18px;
      font-size: 15px; line-height: 1.65;
      word-wrap: break-word;
      white-space: pre-wrap;
    }
    .bubble.user {
      background: #E2E8F0; color: #1E293B;
      border-bottom-right-radius: 6px;
      max-width: 75%;
    }
    .row.grouped .bubble.user { border-top-right-radius: 6px; }
    .bubble.assistant {
      background: #EEF2FB; color: #1E293B;
      border-radius: 12px;
      padding: 16px 20px;
      border: 1px solid #DDE6F5;
      width: 100%;
    }
    .bubble.summary-card {
      background: #E3FCEF; color: #1B6B3A;
      border: 1px solid #A3D9B1;
      border-radius: 12px;
      padding: 16px 20px;
      width: 100%;
    }
    .summary-card .summary-title {
      font-size: 13px; font-weight: 700;
      text-transform: uppercase; letter-spacing: 0.5px;
      color: #1B6B3A; margin-bottom: 12px;
    }
    .summary-card .summary-row {
      margin-bottom: 10px;
    }
    .summary-card .summary-label {
      font-size: 11px; font-weight: 700;
      text-transform: uppercase; letter-spacing: 0.4px;
      color: #2F7A4F; margin-bottom: 3px;
    }
    .summary-card .summary-value {
      font-size: 14px; line-height: 1.5; color: #1B2E1F;
    }

    /* Typing indicator */
    .typing-row { margin-top: 28px; }
    .typing-bubble {
      background: #EEF2FB; border: 1px solid #DDE6F5;
      border-radius: 12px; padding: 14px 18px;
      display: flex; gap: 5px; align-items: center;
    }
    .typing-dot {
      width: 7px; height: 7px; border-radius: 50%;
      background: var(--primary); opacity: 0.4;
      animation: typingBounce 1.2s infinite;
    }
    .typing-dot:nth-child(2) { animation-delay: .2s; }
    .typing-dot:nth-child(3) { animation-delay: .4s; }
    @keyframes typingBounce {
      0%, 60%, 100% { transform: translateY(0);   opacity: .4; }
      30%           { transform: translateY(-5px); opacity: 1;  }
    }

    /* ===== Welcome screen ===== */
    .welcome {
      flex: 1;
      display: flex; flex-direction: column;
      align-items: center; justify-content: center;
      padding: 40px 24px;
      text-align: center;
      gap: 8px;
    }
    .welcome-icon {
      font-size: 40px; line-height: 1; margin-bottom: 8px;
    }
    .welcome-title {
      font-size: 22px; font-weight: 700; color: var(--text); margin-bottom: 6px;
    }
    .welcome-sub {
      font-size: 15px; color: var(--text-muted); max-width: 480px; line-height: 1.6;
      margin-bottom: 28px;
    }
    .example-cards {
      display: flex; flex-direction: column; gap: 10px;
      width: 100%; max-width: 520px;
    }
    .example-cards-label {
      font-size: 11px; font-weight: 700; color: var(--text-subtle);
      text-transform: uppercase; letter-spacing: 0.5px;
      margin-bottom: 2px; text-align: left;
    }
    .example-card {
      padding: 12px 16px 12px 18px;
      border: 1px solid #C7D8F5;
      border-left: 3px solid var(--primary);
      border-radius: 8px;
      background: #F8FAFF;
      font-size: 13.5px; color: #334155;
      line-height: 1.5; text-align: left;
      cursor: pointer;
      transition: all .15s;
    }
    .example-card:hover {
      background: #EEF2FB;
      border-color: var(--primary);
      box-shadow: 0 2px 8px rgba(47,93,169,0.12);
    }

    /* ===== Composer ===== */
    .composer-wrap {
      padding: 10px 16px 14px;
      background: var(--bg);
      border-top: 1px solid rgba(0,0,0,0.06);
      flex-shrink: 0;
    }
    .composer {
      max-width: 720px; margin: 0 auto;
      display: flex; gap: 8px; align-items: flex-end;
      background: #fff;
      border-radius: 24px;
      padding: 8px 8px 8px 16px;
      box-shadow: 0 0 0 1px rgba(0,0,0,0.08), 0 2px 8px rgba(0,0,0,0.06);
      transition: box-shadow .2s;
    }
    .composer:focus-within {
      box-shadow: 0 0 0 2px var(--primary-light), 0 2px 12px rgba(47,93,169,0.12);
    }
    .composer textarea {
      flex: 1; border: none; outline: none; resize: none;
      font-family: inherit; font-size: 15px; line-height: 1.5;
      background: transparent; color: #1E293B;
      max-height: 160px; min-height: 38px;
      padding: 4px 0;
    }
    .composer textarea::placeholder { color: #94A3B8; }
    .composer textarea:disabled { color: #94A3B8; }
    .send-btn {
      width: 36px; height: 36px; border-radius: 50%;
      border: none; cursor: pointer;
      background: var(--primary); color: #fff;
      display: grid; place-items: center;
      flex-shrink: 0;
      transition: background .15s, transform .1s, opacity .15s;
    }
    .send-btn:hover:not(:disabled) { background: var(--primary-dark); transform: scale(1.05); }
    .send-btn:active:not(:disabled) { transform: scale(0.95); }
    .send-btn:disabled { opacity: .35; cursor: not-allowed; }
    .hint { margin-top: 6px; font-size: 11px; text-align: center; color: #94A3B8; }
  </style>
</head>
<body>
  <div class="app">

    <div class="header">
      <div style="flex:1">
        <div class="header-title">AI Fikir Toplama Asistanı</div>
        <div class="header-subtitle">5 soruluk AI geliştirme fikri formu</div>
      </div>
      <button class="btn-new" id="btnNew">+ Yeni Fikir</button>
    </div>

    <div class="chat-wrap" id="chatWrap">
      <div id="welcomeScreen" class="welcome">
        <div class="welcome-icon">💡</div>
        <div class="welcome-title">AI Fikir Asistanı</div>
        <div class="welcome-sub">
          Geliştirmek istediğin AI fikrini anlat, birlikte şekillendirelim.<br>
          Asistan sana 5 kısa soru soracak ve fikrinin özetini oluşturacak.
        </div>
        <div class="example-cards">
          <div class="example-cards-label">Örnek fikirler</div>
          <div class="example-card" onclick="useExample(this)">Müşteri hizmetleri süreçlerini otomatikleştiren bir chatbot</div>
          <div class="example-card" onclick="useExample(this)">Sözleşmeleri analiz edip risk noktalarını tespit eden AI aracı</div>
          <div class="example-card" onclick="useExample(this)">Çalışan onboarding sürecini kişiselleştiren asistan</div>
        </div>
      </div>
      <div class="chat" id="chat" style="display:none"></div>
    </div>

    <div class="composer-wrap">
      <div class="composer">
        <textarea id="userInput" rows="1" placeholder="Cevabınızı yazın…"></textarea>
        <button class="send-btn" id="sendBtn" title="Gönder">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>
          </svg>
        </button>
      </div>
      <div class="hint">Enter ile gönder · Shift+Enter satır atla</div>
    </div>

  </div>

  <script>
    const STORAGE_KEY_THREAD   = "aiIdeaThreadId";
    const STORAGE_KEY_MESSAGES = "aiIdeaMessages";
    const WELCOME_MSG = "Merhaba. AI geliştirme fikrini toparlamak için sana 5 kısa soru soracağım. Öncelikle fikrinin kısa adı veya başlığı nedir?";

    const chatEl   = document.getElementById("chat");
    const chatWrap = document.getElementById("chatWrap");
    const textarea = document.getElementById("userInput");
    const sendBtn  = document.getElementById("sendBtn");
    const btnNew   = document.getElementById("btnNew");

    let threadId     = localStorage.getItem(STORAGE_KEY_THREAD) || null;
    let chatMessages = JSON.parse(localStorage.getItem(STORAGE_KEY_MESSAGES) || "[]");
    let lastRole     = null;
    let chatStarted  = false;

    // Geçmiş varsa chat moduna geç, yoksa welcome screen göster
    if (chatMessages.length > 0) {
      showChat();
      chatMessages.forEach(m => appendBubble(m.content, m.role === "user" ? "user" : "assistant"));
    }
    textarea.focus();

    function showChat() {
      if (chatStarted) return;
      chatStarted = true;
      document.getElementById("welcomeScreen").style.display = "none";
      document.getElementById("chat").style.display = "flex";
    }

    function useExample(el) {
      textarea.value = el.textContent.trim();
      textarea.style.height = "auto";
      textarea.style.height = Math.min(textarea.scrollHeight, 160) + "px";
      textarea.focus();
    }

    // Yeni Fikir — localStorage temizle, sayfayı sıfırla
    btnNew.addEventListener("click", () => {
      if (!confirm("Mevcut konuşma silinecek. Yeni bir fikir başlatmak istiyor musun?")) return;
      localStorage.removeItem(STORAGE_KEY_THREAD);
      localStorage.removeItem(STORAGE_KEY_MESSAGES);
      location.reload();
    });

    // Textarea otomatik yükseklik
    textarea.addEventListener("input", () => {
      textarea.style.height = "auto";
      textarea.style.height = Math.min(textarea.scrollHeight, 160) + "px";
    });

    textarea.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
    });
    sendBtn.addEventListener("click", sendMessage);

    async function sendMessage() {
      const text = textarea.value.trim();
      if (!text || sendBtn.disabled) return;

      showChat();
      appendBubble(text, "user");
      textarea.value = "";
      textarea.style.height = "auto";
      setLoading(true);
      showTyping();

      try {
        const CONTEXT_PATH = window.location.pathname.split("/rest/")[0];
        const API_ENDPOINT = CONTEXT_PATH + "/rest/scriptrunner/latest/custom/aiIdeaChat";

        const response = await fetch(API_ENDPOINT, {
          method: "POST",
          credentials: "same-origin",
          headers: { "Content-Type": "application/json", "Accept": "application/json" },
          body: JSON.stringify({
            thread_id   : threadId,
            message     : text,
            chat_history: chatMessages
          })
        });

        hideTyping();

        const responseText = await response.text();
        let data;
        try {
          data = JSON.parse(responseText);
        } catch (e) {
          console.error("Raw response:", responseText);
          appendBubble("API JSON dönmedi. Tarayıcı console'da detayı kontrol et.", "assistant");
          return;
        }

        if (!response.ok) {
          appendBubble(data.message || data.error || "Bir hata oluştu.", "assistant");
          return;
        }

        if (data.thread_id) {
          threadId = data.thread_id;
          localStorage.setItem(STORAGE_KEY_THREAD, threadId);
        }

        if (data.completed === true) {
          appendSummaryCard(data.idea);
          chatMessages.push({ role: "user",      content: text });
          chatMessages.push({ role: "assistant", content: data.summary });
          localStorage.removeItem(STORAGE_KEY_THREAD);
          localStorage.removeItem(STORAGE_KEY_MESSAGES);
          appendBubble('Fikir özetiniz kaydedildi. Yeni bir fikir girmek için "+ Yeni Fikir" butonunu kullanabilirsiniz.', "assistant");
          textarea.disabled  = true;
          sendBtn.disabled   = true;
        } else {
          const reply = data.message || "Devam edelim. Lütfen bir sonraki bilgiyi paylaş.";
          appendBubble(reply, "assistant");
          chatMessages.push({ role: "user",      content: text });
          chatMessages.push({ role: "assistant", content: reply });
          localStorage.setItem(STORAGE_KEY_MESSAGES, JSON.stringify(chatMessages));
        }

      } catch (err) {
        hideTyping();
        appendBubble("Bağlantı hatası: " + (err.message || String(err)), "assistant");
      } finally {
        setLoading(false);
        textarea.focus();
      }
    }

    function appendBubble(text, role) {
      const isGrouped = (lastRole === role);
      const row = document.createElement("div");
      row.className = "row " + role + (isGrouped ? " grouped" : " group-start");

      if (role === "assistant") {
        const avatar = document.createElement("div");
        avatar.className = "msg-avatar";
        avatar.textContent = "AI";
        row.appendChild(avatar);
      }

      const bubble = document.createElement("div");
      bubble.className = "bubble " + role;
      bubble.textContent = text;
      row.appendChild(bubble);

      chatEl.appendChild(row);
      lastRole = role;
      scrollToBottom();
    }

    function appendSummaryCard(idea) {
      const row = document.createElement("div");
      row.className = "row assistant group-start";

      const avatar = document.createElement("div");
      avatar.className = "msg-avatar";
      avatar.textContent = "AI";
      row.appendChild(avatar);

      const card = document.createElement("div");
      card.className = "bubble summary-card";

      const title = document.createElement("div");
      title.className = "summary-title";
      title.textContent = "AI Fikri Özeti";
      card.appendChild(title);

      const fields = [
        { label: "Fikir Adı",      key: "fikir_adi"      },
        { label: "Problem",        key: "problem"         },
        { label: "Hedef Kitle",    key: "hedef_kitle"     },
        { label: "Çözüm",          key: "cozum"           },
        { label: "Beklenen Fayda", key: "beklenen_fayda"  }
      ];
      fields.forEach(f => {
        if (!idea[f.key]) return;
        const rowEl = document.createElement("div"); rowEl.className = "summary-row";
        const lbl   = document.createElement("div"); lbl.className = "summary-label"; lbl.textContent = f.label;
        const val   = document.createElement("div"); val.className = "summary-value"; val.textContent = idea[f.key];
        rowEl.appendChild(lbl); rowEl.appendChild(val);
        card.appendChild(rowEl);
      });

      row.appendChild(card);
      chatEl.appendChild(row);
      lastRole = "assistant";
      scrollToBottom();
    }

    let typingRow = null;
    function showTyping() {
      typingRow = document.createElement("div");
      typingRow.className = "row assistant typing-row";
      const avatar = document.createElement("div");
      avatar.className = "msg-avatar"; avatar.textContent = "AI";
      const bubble = document.createElement("div");
      bubble.className = "typing-bubble";
      for (let i = 0; i < 3; i++) {
        const dot = document.createElement("div"); dot.className = "typing-dot";
        bubble.appendChild(dot);
      }
      typingRow.appendChild(avatar); typingRow.appendChild(bubble);
      chatEl.appendChild(typingRow);
      scrollToBottom();
    }
    function hideTyping() {
      if (typingRow) { typingRow.remove(); typingRow = null; }
    }

    function setLoading(on) {
      sendBtn.disabled = on;
      textarea.style.opacity = on ? "0.6" : "1";
    }

    function scrollToBottom() {
      requestAnimationFrame(() => { chatWrap.scrollTop = chatWrap.scrollHeight; });
    }
  </script>
</body>
</html>
"""

    return Response.ok(html)
        .type("text/html;charset=UTF-8")
        .build()
}

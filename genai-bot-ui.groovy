import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.BaseScript
import groovy.transform.Field

import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import java.util.concurrent.ConcurrentHashMap

@BaseScript CustomEndpointDelegate delegate

@Field static Map<String, List<Map>> SESSION_MESSAGES = new ConcurrentHashMap<>()

aiIdeaChat(httpMethod: "POST") { MultivaluedMap queryParams, String body ->

    def json = new JsonSlurper().parseText(body ?: "{}")

    String sessionId = json.sessionId ?: UUID.randomUUID().toString()
    String userMessage = json.message?.toString()?.trim()

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
                    fikir_adi: [type: "string", description: "AI fikrinin kısa adı veya başlığı"],
                    problem: [type: "string", description: "Çözülmek istenen problem"],
                    hedef_kitle: [type: "string", description: "Fikrin hedef kullanıcıları veya etkilenen kitlesi"],
                    cozum: [type: "string", description: "AI çözümünün nasıl çalışacağı ve ne yapacağı"],
                    beklenen_fayda: [type: "string", description: "Beklenen iş, müşteri veya kullanıcı faydası"]
                ],
                required: ["fikir_adi", "problem", "hedef_kitle", "cozum", "beklenen_fayda"]
            ]
        ]
    ]]

    def messages = SESSION_MESSAGES.computeIfAbsent(sessionId) {
        [[role: "system", content: systemPrompt]]
    }

    messages << [role: "user", content: userMessage]

    def requestPayload = [
        messages: messages,
        tools: tools,
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

    def azureResponse = new JsonSlurper().parseText(responseText)
    def assistantMessage = azureResponse.choices[0].message

    messages << assistantMessage

    def toolCalls = assistantMessage.tool_calls

    if (toolCalls && toolCalls.size() > 0) {
        def toolCall = toolCalls[0]

        if (toolCall.function.name == "submit_ai_idea") {
            def args = new JsonSlurper().parseText(toolCall.function.arguments)

            String summary = """
AI Fikri Özeti

Fikir Adı:
${args.fikir_adi}

Problem:
${args.problem}

Hedef Kitle:
${args.hedef_kitle}

Çözüm:
${args.cozum}

Beklenen Fayda:
${args.beklenen_fayda}
""".trim()

            SESSION_MESSAGES.remove(sessionId)

            return Response.ok(JsonOutput.toJson([
                sessionId: sessionId,
                completed: true,
                idea: args,
                summary: summary
            ]))
            .type("application/json;charset=UTF-8")
            .build()
        }
    }

    return Response.ok(JsonOutput.toJson([
        sessionId: sessionId,
        completed: false,
        message: assistantMessage.content
    ]))
    .type("application/json;charset=UTF-8")
    .build()
}

aiIdeaChatbotPage(httpMethod: "GET") { MultivaluedMap queryParams ->

    String html = """
<!DOCTYPE html>
<html lang="tr">
<head>
  <meta charset="UTF-8" />
  <title>AI Fikir Toplama Chatbot</title>
  <style>
    body {
      margin: 0;
      font-family: Arial, sans-serif;
      background: #f4f6f8;
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100vh;
    }

    .chat-container {
      width: 420px;
      height: 640px;
      background: #ffffff;
      border-radius: 16px;
      box-shadow: 0 8px 30px rgba(0,0,0,0.12);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .chat-header {
      background: #1f4e79;
      color: white;
      padding: 18px;
      font-size: 18px;
      font-weight: bold;
    }

    .chat-subtitle {
      font-size: 12px;
      font-weight: normal;
      opacity: 0.9;
      margin-top: 4px;
    }

    .chat-messages {
      flex: 1;
      padding: 16px;
      overflow-y: auto;
      background: #f8fafc;
    }

    .message {
      max-width: 85%;
      padding: 10px 12px;
      margin-bottom: 10px;
      border-radius: 12px;
      line-height: 1.4;
      font-size: 14px;
      white-space: pre-wrap;
    }

    .bot {
      background: #e9eef5;
      color: #1f2937;
    }

    .user {
      background: #1f4e79;
      color: white;
      margin-left: auto;
    }

    .summary {
      background: #e8f5e9;
      border: 1px solid #a5d6a7;
      color: #1b5e20;
    }

    .chat-input-area {
      display: flex;
      padding: 12px;
      border-top: 1px solid #e5e7eb;
      background: white;
      gap: 8px;
    }

    input {
      flex: 1;
      padding: 10px;
      border: 1px solid #d1d5db;
      border-radius: 8px;
      font-size: 14px;
    }

    button {
      background: #1f4e79;
      color: white;
      border: none;
      border-radius: 8px;
      padding: 10px 14px;
      cursor: pointer;
      font-weight: bold;
    }

    button:disabled {
      background: #9ca3af;
      cursor: not-allowed;
    }
  </style>
</head>

<body>
  <div class="chat-container">
    <div class="chat-header">
      AI Fikir Toplama Asistanı
      <div class="chat-subtitle">5 soruluk AI geliştirme fikri formu</div>
    </div>

    <div id="messages" class="chat-messages"></div>

    <div class="chat-input-area">
      <input id="userInput" type="text" placeholder="Cevabınızı yazın..." />
      <button id="sendButton" onclick="sendMessage()">Gönder</button>
    </div>
  </div>

  <script>
    const API_ENDPOINT = "/rest/scriptrunner/latest/custom/aiIdeaChatbotPage";

    let sessionId = localStorage.getItem("aiIdeaSessionId");

    if (!sessionId) {
      sessionId = "session-" + Date.now() + "-" + Math.random().toString(36).substring(2);
      localStorage.setItem("aiIdeaSessionId", sessionId);
    }

    const messagesDiv = document.getElementById("messages");
    const input = document.getElementById("userInput");
    const sendButton = document.getElementById("sendButton");

    addMessage("Merhaba. AI geliştirme fikrini toparlamak için sana 5 kısa soru soracağım. Öncelikle fikrinin kısa adı veya başlığı nedir?", "bot");

    input.addEventListener("keydown", function(event) {
      if (event.key === "Enter") {
        sendMessage();
      }
    });

async function sendMessage() {
  const text = input.value.trim();

  if (!text) {
    return;
  }

  addMessage(text, "user");
  input.value = "";
  setLoading(true);

  try {
    const CONTEXT_PATH = window.location.pathname.split("/rest/")[0];
    const API_ENDPOINT = CONTEXT_PATH + "/rest/scriptrunner/latest/custom/aiIdeaChat";

    const response = await fetch(API_ENDPOINT, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "Accept": "application/json"
      },
      body: JSON.stringify({
        sessionId: sessionId,
        message: text
      })
    });

    const responseText = await response.text();

    let data;
    try {
      data = JSON.parse(responseText);
    } catch (e) {
      console.error("Raw response:", responseText);
      addMessage(
        "API JSON dönmedi. Tarayıcı console'da Raw response çıktısını kontrol et. Büyük ihtimalle login, 404 veya ScriptRunner hata sayfası dönüyor.",
        "bot"
      );
      return;
    }

    if (!response.ok) {
      addMessage(data.message || data.error || "Bir hata oluştu.", "bot");
      return;
    }

    if (data.completed === true) {
      addMessage(data.summary, "bot summary");
      localStorage.removeItem("aiIdeaSessionId");
      addMessage("Fikir özeti tamamlandı. Yeni bir fikir girmek için sayfayı yenileyebilirsin.", "bot");
      input.disabled = true;
      sendButton.disabled = true;
    } else {
      addMessage(data.message || "Devam edelim. Lütfen bir sonraki bilgiyi paylaş.", "bot");
    }

  } catch (error) {
    addMessage("Endpoint'e bağlanırken hata oluştu: " + error.message, "bot");
  } finally {
    setLoading(false);
  }
}

    function addMessage(text, type) {
      const div = document.createElement("div");
      div.className = "message " + type;
      div.textContent = text;
      messagesDiv.appendChild(div);
      messagesDiv.scrollTop = messagesDiv.scrollHeight;
    }

    function setLoading(isLoading) {
      sendButton.disabled = isLoading;
      sendButton.textContent = isLoading ? "Bekleyin" : "Gönder";
    }
  </script>
</body>
</html>
"""

    return Response.ok(html)
        .type("text/html;charset=UTF-8")
        .build()
}

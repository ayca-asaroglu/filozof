package Endpoint.idea

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.BaseScript
import org.apache.log4j.Logger
import com.atlassian.jira.component.ComponentAccessor
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.context.IssueContextImpl
import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.web.bean.PagerFilter

@BaseScript
CustomEndpointDelegate delegate

final String DB_POOL = "local"

String clip(String s, int maxLen) {
    if (s == null) return null
    return (s.length() > maxLen) ? s.substring(0, maxLen) : s
}

promptflowchat(httpMethod: "POST") { MultivaluedMap qp, String body ->
    try {
        def parsed = new JsonSlurper().parseText(body ?: "{}")
        if (!(parsed instanceof Map)) {
            return Response.status(400)
                    .entity([ok: false, error: "Invalid JSON body (expected object)"])
                    .build()
        }

        Map input = (Map) parsed
        String question = (input.get("question") ?: "").toString().trim()
        if (!question) {
            return Response.status(400)
                    .entity([ok: false, error: "question is required"])
                    .build()
        }

        def chatHistory = input.get("chat_history") ?: []
        def endpoint = "https://xx"
        if (!endpoint?.trim()) {
            return Response.status(500)
                    .entity([ok: false, error: "PROMPTFLOW_ENDPOINT is not set"])
                    .build()
        }

        // Promptflow payload (no turn conversion)
        def payload = [question: question, chat_history: chatHistory]
        log.info("Promptflow payload: " + clip(JsonOutput.toJson(payload), 4000))

        URL url = new URL(endpoint)
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection()
        conn.setRequestMethod("POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer xx")
        conn.setDoOutput(true)
        conn.setConnectTimeout(10_000)
        conn.setReadTimeout(30_000)

        conn.outputStream.withWriter("UTF-8") { it << JsonOutput.toJson(payload) }

        int status = conn.responseCode
        String responseText = (status >= 200 && status < 300) ? conn.inputStream.text : (conn.errorStream?.text ?: "")
        def j = responseText ? new JsonSlurper().parseText(responseText) : null

        def answer = null
        def isDone = false
        def args = null
        def size = null

        // answer extraction
        [ { j?.answer }, { j?.outputs?.answer }, { j?.result }, { j?.output },
          { j?.outputs?.result }, { j?.outputs?.output } ].each { closure ->
            if (answer == null) {
                try { answer = closure() } catch (ignored) {}
            }
        }
        if (answer == null) answer = "Cevap alınamadı"

        // isDone extraction
        try { isDone = j?.isDone } catch (ignored) {}
        if (isDone == null) {
            try { isDone = j?.outputs?.isDone } catch (ignored) {}
        }
        if (isDone == null) isDone = false

        // args extraction
        [ { j?.args }, { j?.outputs?.args }, { j?.arguments }, { j?.outputs?.arguments } ].each { closure ->
            if (args == null) {
                try { args = closure() } catch (ignored) {}
            }
        }

        // size extraction
        try { size = j?.complexity } catch (ignored) {}
        if (size == null) {
            try { size = j?.outputs?.complexity } catch (ignored) {}
        }
        log.warn("size")
        log.warn(size)
        if (size == null) size = false

        if (status < 200 || status >= 300) {
            return Response.status(502)
                    .entity([
                            ok    : false,
                            error : "Upstream Promptflow error (HTTP ${status})",
                            detail: (answer instanceof String ? answer : JsonOutput.toJson(answer)),
                            raw   : clip(responseText, 2000)
                    ])
                    .build()
        }

        // ===== DB WRITE =====
        def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
        if (!user) {
            return Response.status(401)
                    .entity([ok: false, error: "Unauthorized"])
                    .build()
        }

        def threadId = input.get("thread_id")
        log.warn("THREAD ID")
        log.warn(threadId)

        DatabaseUtil.withSql(DB_POOL) { sql ->
            sql.connection.autoCommit = false
            try {
                if (threadId == null) {
                    def title = clip(question, 60)
                    def snippet = clip(question, 300)
                    def row = sql.firstRow("""
                        INSERT INTO dbo.fibarpr_chat_thread (user_key, title, last_snippet)
                        OUTPUT inserted.thread_id
                        VALUES (?, ?, ?)
                    """, [user.key, title, snippet])
                    threadId = row.thread_id?.toString()
                } else {
                    def ok = sql.firstRow("""
                        SELECT 1 AS ok
                        FROM dbo.fibarpr_chat_thread
                        WHERE thread_id = ? AND user_key = ? AND is_deleted = 0
                    """, [threadId, user.key])
                    if (!ok) throw new RuntimeException("THREAD_NOT_FOUND")
                }

                sql.execute("""
                    INSERT INTO dbo.fibarpr_chat_message (thread_id, role, content)
                    VALUES (?, N'user', ?)
                """, [threadId, question])

                sql.execute("""
                    INSERT INTO dbo.fibarpr_chat_message (thread_id, role, content)
                    VALUES (?, N'assistant', ?)
                """, [threadId, (answer instanceof String ? answer : JsonOutput.toJson(answer))])

                def lastSnippet = clip((answer instanceof String ? answer : JsonOutput.toJson(answer)), 300)
                sql.execute("""
                    UPDATE dbo.fibarpr_chat_thread
                    SET message_count = message_count + 2,
                        last_message_at = SYSUTCDATETIME(),
                        updated_at = SYSUTCDATETIME(),
                        last_snippet = ?
                    WHERE thread_id = ?
                """, [lastSnippet, threadId])

                sql.connection.commit()
            } catch (Exception e) {
                sql.connection.rollback()
                throw e
            } finally {
                sql.connection.autoCommit = true
            }
        }

        if (isDone) {
            try {
                onProcessDone(args, threadId,size)
            } catch (Exception e) {
                log.warn("onProcessDone failed", e)
            }
        }

        return Response.ok([
                ok       : true,
                answer   : (answer instanceof String ? answer : JsonOutput.toJson(answer)),
                thread_id: threadId?.toString()
        ]).build()
    } catch (Throwable t) {
        return Response.status(500)
                .entity([ok: false, error: t.message])
                .build()
    }
}

def onProcessDone(def args, def threadId, def size) {
    log.warn("thread id")
    log.warn(threadId)
    log.warn("size")
    log.warn(size)

    // ======= AYAR =======
    final String PROJECT_KEY = "HC"
    final String ISSUE_TYPE_NAME = "Fikir"

    // ✅ Request Type adı (Portal’daki request type adıyla aynı olmalı)
    final String REQUEST_TYPE_NAME = "Fikir"   // örnek: "Fikir Talebi" / "Öneri" vs.

    // ---------- 1) args normalize + arguments parse ----------
    Map argsMap = [:]
    if (args instanceof Map) {
        argsMap = (Map) args
    } else if (args instanceof String && args?.trim()) {
        argsMap = new JsonSlurper().parseText(args as String) as Map
    }
    log.warn("args")
    log.warn(argsMap)

    def argumentsRaw = argsMap?.arguments
    if (!(argumentsRaw instanceof String) || !argumentsRaw?.trim()) {
        log.warn("onProcessDone: args.arguments boş veya yok")
        return
    }

    Map idea = new JsonSlurper().parseText(argumentsRaw as String) as Map

    // ---------- 2) Jira services ----------
    def issueService = ComponentAccessor.getComponent(IssueService)
    def searchService = ComponentAccessor.getComponent(SearchService)
    def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
    def constants = ComponentAccessor.constantsManager
    def projectManager = ComponentAccessor.projectManager
    def adminUser = ComponentAccessor.getUserManager().getUserByName("admin")
    def project = projectManager.getProjectObjByKey(PROJECT_KEY)
    def issueType = constants.allIssueTypeObjects.find { it.name == ISSUE_TYPE_NAME }

    if (!project || !issueType) {
        log.warn("onProcessDone: Project veya IssueType bulunamadı. project=${project} issueType=${issueType}")
        return
    }

    // Create öncesi CF config bulmak için IssueContext (kanallar için)
    def issueContext = new IssueContextImpl(project.id, issueType.id as String)

    // ---------- 3) Aynı threadId ile daha önce issue var mı? ----------
    def existingIssue = findIssueByThreadId(searchService, adminUser, PROJECT_KEY, ISSUE_TYPE_NAME, threadId)
    log.warn("existing issue")
    log.warn(existingIssue)

    // ---------- 4) Input params ----------
    def params = issueService.newIssueInputParameters()
    params
            .setSummary((idea.fikrin_ozeti ?: "Idea").toString())
            .setDescription((idea.fikrin_aciklamasi ?: "").toString())

    setCf(params, "customfield_18626", idea.amac)
    setCf(params, "customfield_18625", idea.problem)
    setCf(params, "customfield_18627", idea.cozum_tipi)
    setCf(params, "customfield_18628", idea.mevcut_durum)
    setCf(params, "customfield_18629", idea.hedef_kitle)
    setCf(params, "customfield_18630", idea.kpi)
    setCf(params, "customfield_18700", threadId)
    setSelectList(params, "customfield_19007", size, issueContext)


    // Kanallar = multi-select
    def channels = normalizeToList(idea.kanallar)
    setMultiSelect(params, "customfield_10427", channels, issueContext)

    if (existingIssue) {
        // ===== UPDATE =====
        log.warn("onProcessDone: Mevcut issue bulundu (${existingIssue.key}), update ediliyor.")
        def updateParams = issueService.newIssueInputParameters()
        updateParams.setRetainExistingValuesWhenParameterNotProvided(true)

        updateParams
                .setSummary((idea.fikrin_ozeti ?: "Idea").toString())
                .setDescription((idea.fikrin_aciklamasi ?: "").toString())

        setCf(updateParams, "customfield_18626", idea.amac)
        setCf(updateParams, "customfield_18625", idea.problem)
        setCf(updateParams, "customfield_18627", idea.cozum_tipi)
        setCf(updateParams, "customfield_18628", idea.mevcut_durum)
        setCf(updateParams, "customfield_18629", idea.hedef_kitle)
        setCf(updateParams, "customfield_18630", idea.kpi)
        setCf(updateParams, "customfield_18700", threadId)
        setSelectList(updateParams, "customfield_19007", size, issueContext)

        def updatedChannels = normalizeToList(idea.kanallar)
        setMultiSelect(updateParams, "customfield_10427", updatedChannels, issueContext)

        def validation = issueService.validateUpdate(adminUser, existingIssue.id, updateParams)
        if (!validation.valid) {
            log.warn("onProcessDone: update validation failed: ${validation.errorCollection}")
            return
        }

        def result = issueService.update(adminUser, validation)
        if (!result.valid) {
            log.warn("onProcessDone: update failed: ${result.errorCollection}")
            return
        }

        log.info("onProcessDone: Issue updated: ${result.issue?.key}")
    } else {
        // ===== CREATE =====
        log.info("onProcessDone: Bu threadId için issue yok, yeni issue create ediliyor.")
        def authCtx = ComponentAccessor.jiraAuthenticationContext
        def prevUser = authCtx.loggedInUser
        params
                .setProjectId(project.id)
                .setIssueTypeId(issueType.id)
                .setReporterId(user.getUsername())

        try {
            authCtx.setLoggedInUser(adminUser)

            def validation = issueService.validateCreate(adminUser, params)
            if (!validation.valid) {
                log.warn("create validation failed: ${validation.errorCollection}")
                return
            }

            def result = issueService.create(adminUser, validation)
            if (!result.valid) {
                log.warn("create failed: ${result.errorCollection}")
                return
            }

            def createdIssue = result.issue
            log.warn("created: ${createdIssue.key} creator=${createdIssue.creator?.name} reporter=${createdIssue.reporter?.name}")

        } finally {
            authCtx.setLoggedInUser(prevUser)
        }
    }
}

def findIssueByThreadId(def searchService, def user, String projectKey, String issueTypeName, String threadId) {
    if (!threadId?.trim()) return null

    String escaped = threadId.replace('\\', '\\\\').replace('"', '\\"')
    String jql = """\
        project = "${projectKey}"
        AND issuetype = "${issueTypeName}"
        AND cf[18700] ~ "\\"${escaped}\\""
        ORDER BY updated DESC
    """.trim()
    log.warn(jql)

    def parseResult = searchService.parseQuery(user, jql)
    if (!parseResult.valid) {
        log.warn("findIssueByThreadId: Geçersiz JQL: ${parseResult.errors}")
        return null
    }

    def results = searchService.search(user, parseResult.query, PagerFilter.getUnlimitedFilter())
    if (!results || (results.total ?: 0) == 0) return null

    def issueList = []
    if (results.metaClass.respondsTo(results, "getResults")) {
        issueList = results.getResults()
    } else if (results.metaClass.respondsTo(results, "getIssues")) {
        issueList = results.getIssues()
    } else if (results.hasProperty("results")) {
        issueList = results.results
    }

    if (!issueList) return null

    def customField = ComponentAccessor.customFieldManager.getCustomFieldObject("customfield_18700")
    if (!customField) {
        log.warn("findIssueByThreadId: customfield_18700 bulunamadı")
        return null
    }

    def exact = issueList.find { issue ->
        def v = issue.getCustomFieldValue(customField)
        v != null && v.toString() == threadId
    }
    return exact
}

def void setCf(def params, String cfId, def value) {
    if (value == null) return
    String s = value.toString().trim()
    if (!s) return
    params.addCustomFieldValue(cfId, s)
}


def void setMultiSelect(def params, String cfId, List<String> optionValues, def issueContext) {
    if (!optionValues) return

    def customFieldManager = ComponentAccessor.customFieldManager
    def optionsManager = ComponentAccessor.getComponent(OptionsManager)

    CustomField cf = customFieldManager.getCustomFieldObject(cfId)
    if (!cf) return

    def config = cf.getRelevantConfig(issueContext)
    def options = optionsManager.getOptions(config)

    def ids = optionValues.collect { v ->
        def vv = v?.toString()?.trim()
        if (!vv) return null

        def opt = options?.find { it.value == vv } ?: options?.find { it.value?.equalsIgnoreCase(vv) }
        if (!opt) {
            log.warn("MultiSelect option bulunamadı: ${cfId} -> '${vv}'")
            return null
        }
        opt.optionId.toString()
    }.findAll { it }

    if (ids) {
        params.addCustomFieldValue(cfId, (ids as String[]))   // ✅ List -> String[]
    }
}

def void setSelectList(def params, String cfId, def optionValue, def issueContext) {
    if (optionValue == null) return

    String vv = optionValue.toString().trim()
    if (!vv) return

    def customFieldManager = ComponentAccessor.customFieldManager
    def optionsManager = ComponentAccessor.getComponent(OptionsManager)

    CustomField cf = customFieldManager.getCustomFieldObject(cfId)
    if (!cf) return

    def config = cf.getRelevantConfig(issueContext)
    def options = optionsManager.getOptions(config)

    def opt = options?.find { it.value == vv } ?: options?.find { it.value?.equalsIgnoreCase(vv) }

    if (!opt) {
        log.warn("SelectList option bulunamadı: ${cfId} -> '${vv}'")
        return
    }

    params.addCustomFieldValue(cfId, opt.optionId.toString())
}

def List<String> normalizeToList(def raw) {
    if (raw == null) return []

    if (raw instanceof List) {
        return raw.collect { it?.toString()?.trim() }.findAll { it }
    }

    String s = raw.toString().trim()
    if (!s) return []

    if (s.startsWith("[") && s.endsWith("]")) {
        try {
            def parsed = new JsonSlurper().parseText(s)
            if (parsed instanceof List) {
                return parsed.collect { it?.toString()?.trim() }.findAll { it }
            }
        } catch (ignored) {}
    }

    if (s.contains(",")) {
        return s.split(",")*.trim().findAll { it }
    }

    return [s]
}

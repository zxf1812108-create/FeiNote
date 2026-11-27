package com.feiji.bulletnotes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 本地 MDX 词典与配套 CSS 样式文件管理器：
 * - 将用户通过系统文件选择器导入的 .mdx / .css 文件复制到应用私有目录，支持随机高速读取与离线持久使用；
 * - 支持多本词典管理、同名自动匹配 CSS、单独为词典绑定/更换/清除 CSS、启用开关及查词汇总。
 */
object MdxDictionaryManager {

    private const val PREFS_NAME = "mdx_dictionary_prefs"
    private const val KEY_ENTRIES = "dictionary_entries_v1"
    private const val KEY_DEFAULT_CSS_FILE = "default_css_file_v1"
    private const val KEY_DEFAULT_CSS_NAME = "default_css_name_v1"
    private const val KEY_PREFERRED_ID = "preferred_dictionary_id_v1"

    data class DictionaryEntry(
        val id: String,
        val title: String,
        val mdxFileName: String,
        val mdxStoredName: String,
        val cssFileName: String? = null,
        val cssStoredName: String? = null,
        val entryCount: Long = 0L,
        val fileSizeBytes: Long = 0L,
        val enabled: Boolean = true
    ) {
        /** 始终优先展示导入时的文件名称（去除 .mdx 后缀），不采用 MDX 内置元数据标题 */
        val displayFileName: String
            get() = mdxFileName
                .trim()
                .removeSuffix(".mdx")
                .removeSuffix(".MDX")
                .ifBlank { mdxFileName.substringBeforeLast('.').ifBlank { title } }
    }

    data class DictionaryLookupResult(
        val dictionaryId: String,
        val dictionaryTitle: String,
        val queriedWord: String,
        val matchedWord: String,
        val html: String,
        val cssText: String,
        val hasCustomCss: Boolean = cssText.isNotBlank()
    )

    private val gson = Gson()
    private val engineLock = Any()
    private val engineCache = HashMap<String, MdxDictionaryEngine>()

    private val _dictionaries = MutableStateFlow<List<DictionaryEntry>>(emptyList())
    val dictionaries: StateFlow<List<DictionaryEntry>> = _dictionaries.asStateFlow()

    private val _defaultCssName = MutableStateFlow<String?>(null)
    val defaultCssName: StateFlow<String?> = _defaultCssName.asStateFlow()

    private val _preferredDictionaryId = MutableStateFlow<String?>(null)
    val preferredDictionaryId: StateFlow<String?> = _preferredDictionaryId.asStateFlow()

    private var initialized = false

    private fun getDictDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "mdx_dictionaries")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(engineLock) {
            if (initialized) return
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_ENTRIES, null)
            val parsed: List<DictionaryEntry> = if (!raw.isNullOrBlank()) {
                runCatching {
                    val type = object : TypeToken<List<DictionaryEntry>>() {}.type
                    gson.fromJson<List<DictionaryEntry>>(raw, type) ?: emptyList()
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val dir = getDictDir(appCtx)
            val valid = parsed.filter { File(dir, it.mdxStoredName).exists() }
            _dictionaries.value = valid

            val defCssStored = prefs.getString(KEY_DEFAULT_CSS_FILE, null)
            val defCssDisplay = prefs.getString(KEY_DEFAULT_CSS_NAME, null)
            if (defCssStored != null && File(dir, defCssStored).exists()) {
                _defaultCssName.value = defCssDisplay ?: defCssStored
            } else {
                _defaultCssName.value = null
            }

            val savedPreferred = prefs.getString(KEY_PREFERRED_ID, null)
            _preferredDictionaryId.value = savedPreferred?.takeIf { prefId ->
                valid.any { it.id == prefId }
            }
            initialized = true
        }
    }

    private fun persist(context: Context, list: List<DictionaryEntry>) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(list)).apply()
        _dictionaries.value = list
    }

    suspend fun importMdx(context: Context, uri: Uri): Result<DictionaryEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureLoaded(context)
                val appCtx = context.applicationContext
                val displayName = resolveDisplayName(appCtx, uri) ?: "dictionary.mdx"
                if (!displayName.endsWith(".mdx", ignoreCase = true)) {
                    error("请选择后缀为 .mdx 的词典文件")
                }

                val dir = getDictDir(appCtx)
                val id = UUID.randomUUID().toString()
                val storedName = "${id}.mdx"
                val destFile = File(dir, storedName)

                try {
                    appCtx.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    } ?: error("无法读取所选 MDX 文件")

                    // 立即试解析以验证文件合法性并提取词条总数，显示名直接使用文件名称（去除 .mdx 后缀）
                    val engine = MdxDictionaryEngine.open(destFile)
                    val title = displayName
                        .trim()
                        .removeSuffix(".mdx")
                        .removeSuffix(".MDX")
                        .ifBlank { displayName.substringBeforeLast('.').ifBlank { displayName } }
                    val entryCount = engine.numEntries
                    val sizeBytes = destFile.length()

                    synchronized(engineLock) {
                        engineCache[id] = engine
                    }

                    // 若当前存在同名前缀的默认 CSS，自动提示复用（未单独绑定时查词会自动回退到默认 CSS）
                    val newEntry = DictionaryEntry(
                        id = id,
                        title = title,
                        mdxFileName = displayName,
                        mdxStoredName = storedName,
                        entryCount = entryCount,
                        fileSizeBytes = sizeBytes,
                        enabled = true
                    )
                    val updated = _dictionaries.value + newEntry
                    persist(appCtx, updated)
                    newEntry
                } catch (e: Throwable) {
                    runCatching { destFile.delete() }
                    throw e
                }
            }
        }

    /**
     * 导入 CSS 样式文件：
     * - 若指定 targetDictionaryId，则直接绑定到该词典；
     * - 否则优先按文件名前缀匹配已有词典，或当仅有一本词典时直接绑定，否则设为全局默认 CSS。
     */
    suspend fun importCss(
        context: Context,
        uri: Uri,
        targetDictionaryId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureLoaded(context)
            val appCtx = context.applicationContext
            val displayName = resolveDisplayName(appCtx, uri) ?: "style.css"
            if (!displayName.endsWith(".css", ignoreCase = true)) {
                error("请选择后缀为 .css 的样式文件")
            }

            val dir = getDictDir(appCtx)
            val currentList = _dictionaries.value

            val resolvedTargetId = targetDictionaryId ?: run {
                val cssStem = displayName.substringBeforeLast('.').lowercase()
                currentList.firstOrNull {
                    it.mdxFileName.substringBeforeLast('.').lowercase() == cssStem
                }?.id ?: if (currentList.size == 1) currentList.first().id else null
            }

            val storedName = "css_${UUID.randomUUID()}.css"
            val destFile = File(dir, storedName)
            appCtx.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            } ?: error("无法读取所选 CSS 文件")

            if (resolvedTargetId != null) {
                val target = currentList.firstOrNull { it.id == resolvedTargetId }
                if (target != null) {
                    target.cssStoredName?.let { oldCss ->
                        runCatching { File(dir, oldCss).delete() }
                    }
                    val updated = currentList.map {
                        if (it.id == resolvedTargetId) {
                            it.copy(cssFileName = displayName, cssStoredName = storedName)
                        } else {
                            it
                        }
                    }
                    persist(appCtx, updated)
                    return@runCatching "已将「$displayName」绑定到「${target.displayFileName}」"
                }
            }

            // 设为全局默认 CSS（供所有未单独绑定 CSS 的词典使用）
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_DEFAULT_CSS_FILE, null)?.let { oldDefault ->
                runCatching { File(dir, oldDefault).delete() }
            }
            prefs.edit()
                .putString(KEY_DEFAULT_CSS_FILE, storedName)
                .putString(KEY_DEFAULT_CSS_NAME, displayName)
                .apply()
            _defaultCssName.value = displayName
            "已导入默认样式「$displayName」"
        }
    }

    /**
     * 支持单选或多选导入（可一次选中 .mdx 词库及其配套 .css 样式，自动按同名优先绑定）。
     */
    suspend fun importFiles(context: Context, uris: List<Uri>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureLoaded(context)
                val appCtx = context.applicationContext
                if (uris.isEmpty()) error("未选择文件")

                val named = uris.map { uri -> uri to (resolveDisplayName(appCtx, uri) ?: "") }
                val mdxUris = named.filter { it.second.endsWith(".mdx", ignoreCase = true) }
                val cssUris = named.filter { it.second.endsWith(".css", ignoreCase = true) }

                if (mdxUris.isEmpty() && cssUris.isEmpty()) {
                    error("请选择后缀为 .mdx 的词典文件或 .css 样式文件")
                }

                val importedTitles = mutableListOf<String>()
                var cssCount = 0
                var lastCssMsg: String? = null
                var firstError: Throwable? = null

                for ((uri, _) in mdxUris) {
                    importMdx(appCtx, uri)
                        .onSuccess { importedTitles.add(it.title) }
                        .onFailure { if (firstError == null) firstError = it }
                }
                for ((uri, _) in cssUris) {
                    importCss(appCtx, uri, targetDictionaryId = null)
                        .onSuccess { msg ->
                            cssCount++
                            lastCssMsg = msg
                        }
                        .onFailure { if (firstError == null) firstError = it }
                }

                when {
                    importedTitles.size == 1 && cssCount == 0 ->
                        "已导入「${importedTitles.first()}」"
                    importedTitles.isNotEmpty() && cssCount > 0 ->
                        "已导入 ${importedTitles.size} 本词典与 $cssCount 份样式"
                    importedTitles.size > 1 ->
                        "已导入 ${importedTitles.size} 本词典"
                    cssCount > 0 ->
                        lastCssMsg ?: "已导入 $cssCount 份样式"
                    else -> throw (firstError ?: IllegalStateException("导入词库失败"))
                }
            }
        }

    fun clearDictionaryCss(context: Context, dictionaryId: String) {
        ensureLoaded(context)
        val appCtx = context.applicationContext
        val dir = getDictDir(appCtx)
        val updated = _dictionaries.value.map { entry ->
            if (entry.id == dictionaryId) {
                entry.cssStoredName?.let { runCatching { File(dir, it).delete() } }
                entry.copy(cssFileName = null, cssStoredName = null)
            } else {
                entry
            }
        }
        persist(appCtx, updated)
    }

    fun clearDefaultCss(context: Context) {
        ensureLoaded(context)
        val appCtx = context.applicationContext
        val dir = getDictDir(appCtx)
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEFAULT_CSS_FILE, null)?.let { old ->
            runCatching { File(dir, old).delete() }
        }
        prefs.edit()
            .remove(KEY_DEFAULT_CSS_FILE)
            .remove(KEY_DEFAULT_CSS_NAME)
            .apply()
        _defaultCssName.value = null
    }

    fun setDictionaryEnabled(context: Context, dictionaryId: String, enabled: Boolean) {
        ensureLoaded(context)
        val updated = _dictionaries.value.map { entry ->
            if (entry.id == dictionaryId) entry.copy(enabled = enabled) else entry
        }
        persist(context, updated)
    }

    fun togglePreferredDictionary(context: Context, dictionaryId: String) {
        ensureLoaded(context)
        val appCtx = context.applicationContext
        val nextId = if (_preferredDictionaryId.value == dictionaryId) null else dictionaryId
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (nextId != null) {
            prefs.edit().putString(KEY_PREFERRED_ID, nextId).apply()
        } else {
            prefs.edit().remove(KEY_PREFERRED_ID).apply()
        }
        _preferredDictionaryId.value = nextId
    }

    fun reorderDictionaries(context: Context, orderedIds: List<String>) {
        ensureLoaded(context)
        val byId = _dictionaries.value.associateBy { it.id }
        val reordered = buildList {
            for (id in orderedIds) {
                byId[id]?.let { add(it) }
            }
            for (entry in _dictionaries.value) {
                if (entry.id !in orderedIds) add(entry)
            }
        }
        if (reordered != _dictionaries.value) {
            persist(context, reordered)
        }
    }

    fun deleteDictionary(context: Context, dictionaryId: String) {
        ensureLoaded(context)
        val appCtx = context.applicationContext
        val dir = getDictDir(appCtx)
        val target = _dictionaries.value.firstOrNull { it.id == dictionaryId } ?: return
        synchronized(engineLock) {
            engineCache.remove(dictionaryId)?.close()
        }
        runCatching { File(dir, target.mdxStoredName).delete() }
        target.cssStoredName?.let { runCatching { File(dir, it).delete() } }
        val updated = _dictionaries.value.filterNot { it.id == dictionaryId }
        if (_preferredDictionaryId.value == dictionaryId) {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PREFERRED_ID)
                .apply()
            _preferredDictionaryId.value = null
        }
        persist(appCtx, updated)
    }

    private fun resolveOrderedEnabledDictionaries(): List<DictionaryEntry> {
        val enabledDicts = _dictionaries.value.filter { it.enabled }
        val prefId = _preferredDictionaryId.value
        return if (prefId != null) {
            val preferred = enabledDicts.firstOrNull { it.id == prefId }
            if (preferred != null) {
                listOf(preferred) + enabledDicts.filterNot { it.id == prefId }
            } else {
                enabledDicts
            }
        } else {
            enabledDicts
        }
    }

    suspend fun lookupAcrossDictionaries(
        context: Context,
        query: String
    ): List<DictionaryLookupResult> = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        val trimmed = MdxDictionaryEngine.cleanBasicQuery(query)
        if (trimmed.isEmpty()) return@withContext emptyList()

        val appCtx = context.applicationContext
        val dir = getDictDir(appCtx)
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultCssStored = prefs.getString(KEY_DEFAULT_CSS_FILE, null)
        val defaultCssContent = defaultCssStored
            ?.let { File(dir, it) }
            ?.takeIf { it.exists() }
            ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }
            .orEmpty()

        val orderedDicts = resolveOrderedEnabledDictionaries()
        val results = ArrayList<DictionaryLookupResult>(orderedDicts.size)

        for (entry in orderedDicts) {
            val engine = getOrOpenEngine(dir, entry) ?: continue
            val match = runCatching { engine.lookup(trimmed) }.getOrNull() ?: continue
            val customCss = entry.cssStoredName
                ?.let { File(dir, it) }
                ?.takeIf { it.exists() }
                ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
            val cssText = customCss ?: defaultCssContent

            results.add(
                DictionaryLookupResult(
                    dictionaryId = entry.id,
                    dictionaryTitle = entry.displayFileName,
                    queriedWord = match.queriedWord,
                    matchedWord = match.matchedWord,
                    html = match.html,
                    cssText = cssText,
                    hasCustomCss = cssText.isNotBlank()
                )
            )
        }
        results
    }

    /**
     * 跨所有已启用词典按优先级前缀联想，去重后返回候选词列表。
     */
    suspend fun suggestAcrossDictionaries(
        context: Context,
        prefix: String,
        limit: Int = 16
    ): List<String> = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        val trimmed = MdxDictionaryEngine.cleanBasicQuery(prefix)
        if (trimmed.isEmpty() || limit <= 0) return@withContext emptyList()

        val appCtx = context.applicationContext
        val dir = getDictDir(appCtx)
        val orderedDicts = resolveOrderedEnabledDictionaries()
        val results = ArrayList<String>(limit)
        val seenLower = HashSet<String>(limit * 2)

        for (entry in orderedDicts) {
            if (results.size >= limit) break
            val engine = getOrOpenEngine(dir, entry) ?: continue
            val hits = runCatching { engine.suggestPrefix(trimmed, limit) }.getOrDefault(emptyList())
            for (word in hits) {
                val key = word.lowercase(java.util.Locale.ROOT)
                if (seenLower.add(key)) {
                    results.add(word)
                    if (results.size >= limit) break
                }
            }
        }
        results
    }

    private fun getOrOpenEngine(dir: File, entry: DictionaryEntry): MdxDictionaryEngine? {
        synchronized(engineLock) {
            engineCache[entry.id]?.let { return it }
            val mdxFile = File(dir, entry.mdxStoredName)
            if (!mdxFile.exists()) return null
            val opened = runCatching { MdxDictionaryEngine.open(mdxFile) }.getOrNull() ?: return null
            engineCache[entry.id] = opened
            return opened
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }
}

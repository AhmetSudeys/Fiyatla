package com.ahmetsudeys.rotauygulama.data.materials

import android.content.Context
import com.ahmetsudeys.rotauygulama.data.model.MaterialItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persists user-managed material lists.
 *
 * Two kinds of entries live here:
 *  - **custom** lists: created by the user from scratch. They show up as new tabs in the
 *    Materials screen and as selectable operations in the quote flow.
 *  - **materialized** Excel lists: when the user structurally edits a built-in Excel list
 *    (renames/adds/deletes a row, or changes qty/price), we snapshot its current items here.
 *    From then on this store is the source of truth for that list, so edits are fully dynamic.
 *
 * Kept intentionally small (JSON in SharedPreferences); lists are only a few dozen rows.
 */
object MaterialListStore {
    private const val FILE = "rota_material_lists"
    private const val KEY = "lists_json"

    data class StoredList(
        val name: String,
        val custom: Boolean,
        val items: List<MaterialItem>
    )

    /** Display names of user-created (custom) lists, in the order the user added them. */
    fun getCustomListNames(context: Context): List<String> {
        val root = readJson(context)
        val order = root.optJSONArray("order") ?: JSONArray()
        val lists = root.optJSONObject("lists") ?: JSONObject()
        val names = ArrayList<String>(order.length())
        for (i in 0 until order.length()) {
            val key = order.optString(i)
            val obj = lists.optJSONObject(key) ?: continue
            if (obj.optBoolean("custom", false)) {
                names.add(obj.optString("name", key))
            }
        }
        return names
    }

    /** Returns the stored list (custom or materialized) for [listName], or null if none. */
    fun getStoredList(context: Context, listName: String): StoredList? {
        val root = readJson(context)
        val lists = root.optJSONObject("lists") ?: return null
        val obj = lists.optJSONObject(listName.keyNorm()) ?: return null
        return StoredList(
            name = obj.optString("name", listName),
            custom = obj.optBoolean("custom", false),
            items = obj.optJSONArray("items").toMaterialItems()
        )
    }

    fun exists(context: Context, listName: String): Boolean {
        val lists = readJson(context).optJSONObject("lists") ?: return false
        return lists.has(listName.keyNorm())
    }

    fun isCustom(context: Context, listName: String): Boolean {
        return getStoredList(context, listName)?.custom == true
    }

    /**
     * Creates a new empty custom list. Returns false if a list with the same (normalized) name
     * already exists in the store or in [reservedNames] (e.g. built-in Excel sheet names).
     */
    fun createCustomList(context: Context, displayName: String, reservedNames: List<String>): Boolean {
        val name = displayName.trim()
        if (name.isBlank()) return false
        val key = name.keyNorm()
        val root = readJson(context)
        val lists = root.optJSONObject("lists") ?: JSONObject().also { root.put("lists", it) }
        if (lists.has(key)) return false
        if (reservedNames.any { it.keyNorm() == key }) return false

        val obj = JSONObject().apply {
            put("name", name)
            put("custom", true)
            put("items", JSONArray())
        }
        lists.put(key, obj)

        val order = root.optJSONArray("order") ?: JSONArray().also { root.put("order", it) }
        order.put(key)

        writeJson(context, root)
        return true
    }

    /**
     * Creates a new custom list pre-seeded with [items]. Used when the user renames a built-in
     * Excel list: we snapshot its rows into a fresh custom list under the new name and then hide
     * the original built-in. Returns false on blank name or a name clash.
     */
    fun createCustomListWithItems(
        context: Context,
        displayName: String,
        items: List<MaterialItem>,
        reservedNames: List<String>
    ): Boolean {
        val name = displayName.trim()
        if (name.isBlank()) return false
        val key = name.keyNorm()
        val root = readJson(context)
        val lists = root.optJSONObject("lists") ?: JSONObject().also { root.put("lists", it) }
        if (lists.has(key)) return false
        if (reservedNames.any { it.keyNorm() == key }) return false

        val obj = JSONObject().apply {
            put("name", name)
            put("custom", true)
            put("items", items.toJsonArray())
        }
        lists.put(key, obj)

        val order = root.optJSONArray("order") ?: JSONArray().also { root.put("order", it) }
        order.put(key)

        writeJson(context, root)
        return true
    }

    /** Normalized keys of built-in Excel lists the user has deleted (tombstoned). */
    fun getHiddenBuiltInKeys(context: Context): Set<String> {
        val hidden = readJson(context).optJSONArray("hidden") ?: return emptySet()
        val out = HashSet<String>(hidden.length())
        for (i in 0 until hidden.length()) {
            out.add(hidden.optString(i))
        }
        return out
    }

    fun isBuiltInHidden(context: Context, listName: String): Boolean {
        return getHiddenBuiltInKeys(context).contains(listName.keyNorm())
    }

    /** Tombstones a built-in Excel list so it no longer shows up as a tab. */
    fun hideBuiltIn(context: Context, listName: String) {
        val key = listName.keyNorm()
        val root = readJson(context)
        val hidden = root.optJSONArray("hidden") ?: JSONArray().also { root.put("hidden", it) }
        for (i in 0 until hidden.length()) {
            if (hidden.optString(i) == key) return
        }
        hidden.put(key)
        writeJson(context, root)
    }

    /** Renames a custom list. Returns false on blank name or a name clash. */
    fun renameCustomList(
        context: Context,
        oldName: String,
        newDisplayName: String,
        reservedNames: List<String>
    ): Boolean {
        val newName = newDisplayName.trim()
        if (newName.isBlank()) return false
        val oldKey = oldName.keyNorm()
        val newKey = newName.keyNorm()

        val root = readJson(context)
        val lists = root.optJSONObject("lists") ?: return false
        val obj = lists.optJSONObject(oldKey) ?: return false

        if (newKey != oldKey) {
            if (lists.has(newKey)) return false
            if (reservedNames.any { it.keyNorm() == newKey }) return false
        }

        obj.put("name", newName)
        lists.remove(oldKey)
        lists.put(newKey, obj)

        // Keep display order stable.
        val order = root.optJSONArray("order") ?: JSONArray()
        val newOrder = JSONArray()
        for (i in 0 until order.length()) {
            val k = order.optString(i)
            newOrder.put(if (k == oldKey) newKey else k)
        }
        root.put("order", newOrder)

        writeJson(context, root)
        return true
    }

    /** Removes a stored list. For a materialized Excel list this reverts it to the built-in data. */
    fun deleteList(context: Context, listName: String) {
        val key = listName.keyNorm()
        val root = readJson(context)
        val lists = root.optJSONObject("lists") ?: return
        if (!lists.has(key)) return
        lists.remove(key)

        val order = root.optJSONArray("order") ?: JSONArray()
        val newOrder = JSONArray()
        for (i in 0 until order.length()) {
            val k = order.optString(i)
            if (k != key) newOrder.put(k)
        }
        root.put("order", newOrder)

        writeJson(context, root)
    }

    /**
     * Persists the full ordered [items] for [listName]. Preserves the existing custom flag/name;
     * if the list is new here it is stored as a materialized (non-custom) list named [displayName].
     */
    fun saveItems(context: Context, listName: String, displayName: String, items: List<MaterialItem>) {
        val key = listName.keyNorm()
        val root = readJson(context)
        val lists = root.optJSONObject("lists") ?: JSONObject().also { root.put("lists", it) }
        val existing = lists.optJSONObject(key)

        val obj = JSONObject().apply {
            put("name", existing?.optString("name") ?: displayName)
            put("custom", existing?.optBoolean("custom", false) ?: false)
            put("items", items.toJsonArray())
        }
        lists.put(key, obj)
        writeJson(context, root)
    }

    private fun JSONArray?.toMaterialItems(): List<MaterialItem> {
        if (this == null) return emptyList()
        val out = ArrayList<MaterialItem>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            out.add(
                MaterialItem(
                    name = o.optString("n", ""),
                    quantity = o.optDouble("q", 0.0),
                    price = o.optDouble("p", 0.0)
                )
            )
        }
        return out
    }

    private fun List<MaterialItem>.toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (item in this) {
            arr.put(
                JSONObject().apply {
                    put("n", item.name)
                    put("q", item.quantity)
                    put("p", item.price)
                }
            )
        }
        return arr
    }

    private fun readJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "{}").orEmpty()
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun writeJson(context: Context, obj: JSONObject) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    private fun String.keyNorm(): String {
        return this
            .lowercase(Locale.forLanguageTag("tr-TR"))
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('ş', 's')
            .replace('Ş', 's')
            .replace('ğ', 'g')
            .replace('Ğ', 'g')
            .replace('ç', 'c')
            .replace('Ç', 'c')
            .replace('ö', 'o')
            .replace('Ö', 'o')
            .replace('ü', 'u')
            .replace('Ü', 'u')
            .trim()
    }
}

package com.eko.dcvisits.app.tracking

import com.eko.dcvisits.app.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Plain-JVM stand-in for Context.getString: resolves the real R.string ids
 * against the real res/values and res/values-hi strings.xml files, so tests check the shipped copy
 * (not a duplicate of it) without Robolectric.
 */
object TestStrings {
    private val idToName: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** name -> raw text (after Android's \' \" \n unescaping and quote stripping). */
    fun load(valuesDir: String = "values"): Map<String, String> {
        val file = File("src/main/res/$valuesDir/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to unescape(e.textContent)
        }
    }

    /** name -> the set of quantity texts for each <plurals>. */
    fun loadPlurals(valuesDir: String = "values"): Map<String, List<String>> {
        val file = File("src/main/res/$valuesDir/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            e.getAttribute("name") to (0 until items.length).map { unescape(items.item(it).textContent) }
        }
    }

    fun lookup(valuesDir: String = "values"): StringLookup {
        val strings = load(valuesDir)
        return StringLookup { id, args ->
            val name = idToName[id] ?: error("unknown string id $id")
            val raw = strings[name] ?: error("string '$name' missing from $valuesDir")
            if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
        }
    }

    private fun unescape(s: String): String {
        val t = s.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
        return t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
    }
}

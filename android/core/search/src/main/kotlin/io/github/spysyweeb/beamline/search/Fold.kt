package io.github.spysyweeb.beamline.search

import java.text.Normalizer

/**
 * The search fold (packbuilder/search/normalize.py fold(), steps 1-4): NFKD, combining marks
 * dropped, lower case, the apostrophes ' ’ ` removed, "&" read as " and ", then every run of
 * characters other than a-z and 0-9 as one space, trimmed. One folding for the engine's names
 * ([SearchNormalizer.fold] calls this) and for the place-name rules of saved places and recents
 * (docs/architecture-saved-places.md SP22, SP30, SP55 rule 2). Saved names use their own key,
 * [io.github.spysyweeb.beamline.search.saved.savedNameKey] (SP26), because this one keeps only
 * a-z and 0-9.
 */
fun foldText(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    val nfkd = Normalizer.normalize(text, Normalizer.Form.NFKD)
    val sb = StringBuilder(nfkd.length)
    var i = 0
    while (i < nfkd.length) {
        val cp = nfkd.codePointAt(i)
        if (!Py.isCombining(cp)) sb.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    var t = sb.toString().lowercase()
    t = t.replace("'", "").replace("’", "").replace("`", "")
    t = t.replace("&", " and ")
    val out = StringBuilder(t.length)
    var inRun = false
    for (c in t) {
        if (c in 'a'..'z' || c in '0'..'9') {
            out.append(c)
            inRun = false
        } else if (!inRun) {
            out.append(' ')
            inRun = true
        }
    }
    return out.toString().trim(' ')
}
